package scraml
import cats.effect.IO
import cats.implicits.toTraverseOps
import io.vrap.rmf.raml.model.modules.Api
import io.vrap.rmf.raml.model.types._
import org.scalafmt.interfaces.Scalafmt

import java.io.File
import java.time.LocalDateTime
import scala.meta._

sealed trait GeneratedSource {
  def name: String
  def packageName: String
  def source: Tree
  def comment: String
  def companion: Option[Tree]
}

final case class TypeRef(
    scalaType: Type,
    packageName: Option[String] = None,
    defaultValue: Option[Term] = None
)

final case class GeneratedTypeSource(
    name: String,
    source: Tree,
    packageName: String,
    comment: String,
    companion: Option[Tree]
) extends GeneratedSource

final case class GeneratedFile(source: GeneratedSource, file: File)
final case class GeneratedPackage(sources: List[GeneratedSource] = List.empty) {
  def withSource(source: GeneratedSource): GeneratedPackage = copy(sources = sources :+ source)
}

final case class GeneratedPackages(packages: Map[String, GeneratedPackage] = Map.empty) {
  def addSource(source: GeneratedSource): GeneratedPackages = copy(packages = {
    packages + (source.packageName -> packages
      .getOrElse(source.packageName, GeneratedPackage())
      .withSource(source))
  })
}

object DefaultModelGen extends ModelGen {
  import MetaUtil._
  import RMFUtil._

  private def caseClassSource(context: ModelGenContext): Defn.Class = {
    Defn.Class(
      mods = List(Mod.Final(), Mod.Case()),
      name = Type.Name(context.objectType.getName),
      tparams = Nil,
      ctor = Ctor.Primary(
        mods = Nil,
        name = Name.Anonymous(),
        paramss = List(context.typeParams)
      ),
      templ = Template(
        early = Nil,
        inits = initFromTypeOpt(context.scalaBaseType.map(_.scalaType)) ++ initFromTypeOpt(
          context.extendType
        ),
        self = Self(
          name = Name.Anonymous(),
          decltpe = None
        ),
        stats = Nil
      )
    )
  }

  private def initFromTypeOpt(aType: Option[Type]): List[Init] =
    aType.map(ref => List(Init(ref, Name(""), Nil))).getOrElse(Nil)

  private def companionObjectSource(objectType: ObjectType): Defn.Object = {
    val typeName = objectType.getName
    Defn.Object(
      List(),
      Term.Name(typeName),
      Template(
        early = Nil,
        inits = Nil,
        self = Self(
          name = Name.Anonymous(),
          decltpe = None
        ),
        stats = Nil
      )
    )
  }

  private def caseObjectSource(context: ModelGenContext): Defn.Object = {
    Defn.Object(
      mods = List(Mod.Case()),
      Term.Name(context.objectType.getName),
      Template(
        early = Nil,
        inits = initFromTypeOpt(context.scalaBaseType.map(_.scalaType)) ++ initFromTypeOpt(
          context.extendType
        ),
        Self(Name(""), None),
        stats = Nil,
        derives = Nil
      )
    )
  }

  private def traitSource(context: ModelGenContext): Defn.Trait = {
    val objectType = context.objectType
    val defs = context.typeProperties.flatMap { property =>
      ModelGen
        .scalaTypeRef(property.getType, !property.getRequired, None, context.anyTypeName)
        .map { scalaType =>
          Decl.Def(
            Nil,
            Term.Name(property.getName),
            tparams = Nil,
            paramss = Nil,
            scalaType.scalaType
          )
        }
    }.toList

    val sealedModOpt: Option[Mod.Sealed] =
      if (context.isSealed) {
        Some(Mod.Sealed())
      } else None

    Defn.Trait(
      mods = sealedModOpt.toList,
      name = Type.Name(objectType.getName),
      tparams = Nil,
      ctor = Ctor.Primary(Nil, Name(""), Nil),
      templ = Template(
        early = Nil,
        inits = initFromTypeOpt(context.scalaBaseType.map(_.scalaType)) ++ initFromTypeOpt(
          context.extendType
        ),
        self = Self(Name(""), None),
        stats = defs,
        derives = Nil
      )
    )
  }

  private def objectTypeSource(
      objectType: ObjectType,
      params: ModelGenParams,
      api: ApiContext
  ): IO[GeneratedTypeSource] = {
    for {
      packageName <- IO.fromOption(getPackageName(objectType))(
        new IllegalStateException("object type should have package name")
      )
      apiBaseType = Option(objectType.asInstanceOf[AnyType].getType)
      extendType = getAnnotation(objectType)("scala-extends")
        .map(_.getValue.getValue.toString)
        .map(typeFromName)
      context = ModelGenContext(packageName, objectType, params, api, apiBaseType, extendType)

      discriminator = Option(objectType.getDiscriminator)
      isAbstract = getAnnotation(objectType)("abstract").exists(
        _.getValue.getValue.toString.toBoolean
      )
      isMapType = getAnnotation(objectType)("asMap").isDefined

      source =
        discriminator match {
          case Some(_) =>
            LibrarySupport.applyTrait(
              traitSource(context),
              Some(companionObjectSource(objectType))
            )(params.allLibraries, context)

          case None if isAbstract || context.getSubTypes.nonEmpty =>
            LibrarySupport.applyTrait(
              traitSource(context),
              Some(companionObjectSource(objectType))
            )(params.allLibraries, context)

          case None if !isMapType && context.typeProperties.isEmpty =>
            LibrarySupport.applyObject(caseObjectSource(context))(params.allLibraries, context)

          case None =>
            LibrarySupport.applyClass(
              caseClassSource(context),
              Some(companionObjectSource(objectType))
            )(params.allLibraries, context)
        }
      docsUri = getAnnotation(objectType)("docs-uri").flatMap(annotation =>
        Option(annotation.getValue).map(_.getValue.toString)
      )
      dateCreated = if(params.generateDateCreated) s"* date created: ${LocalDateTime.now()}" else ""
      comment =
        s"""/**
           |* generated by sbt-scraml, do not modify manually
           |* $dateCreated
           |* ${docsUri
          .map("see " + _)
          .orElse(Option(objectType.getDescription))
          .getOrElse(s"generated type for ${objectType.getName}")}
           |*/""".stripMargin
    } yield GeneratedTypeSource(
      objectType.getName,
      source.defn,
      packageName,
      comment,
      source.companion
    )
  }

  private def appendSource(
      file: File,
      source: GeneratedSource,
      formatConfig: Option[File],
      formatter: Scalafmt
  ): IO[GeneratedFile] = {
    val sourceString =
      s"${source.comment}\n${source.source.toString()}\n${source.companion.map(_.toString() + "\n").getOrElse("")}\n"
    val formattedSource = formatConfig match {
      case Some(configFile) if configFile.exists() =>
        formatter.format(configFile.toPath, file.toPath, sourceString)
      case _ => sourceString
    }
    FileUtil.writeToFile(file, formattedSource, append = true).map(GeneratedFile(source, _))
  }

  private def basePackageFilePath(params: ModelGenParams) =
    s"${params.targetDir}/${params.basePackage.replace(".", File.separatorChar.toString)}"

  private def writePackages(
      generated: GeneratedPackages,
      params: ModelGenParams
  ): IO[GeneratedModel] = {
    val scalafmt = Scalafmt.create(this.getClass.getClassLoader)
    val generate = generated.packages.map { case (name, generatedPackage) =>
      for {
        file <- IO {
          val packageFile = new File(s"${basePackageFilePath(params)}/$name.scala")
          packageFile.getParentFile.mkdirs()
          packageFile
        }
        packageStatement = Pkg(packageTerm(s"${params.basePackage}"), Nil).toString()
        withPackage <- FileUtil.writeToFile(file, s"$packageStatement\n\n")
        files <- generatedPackage.sources
          .map(appendSource(withPackage, _, params.formatConfig, scalafmt))
          .sequence
      } yield files
    }

    generate.toList.sequence.map(_.flatten).map(GeneratedModel(_))
  }

  private def generatePackages(api: ApiContext, params: ModelGenParams): IO[GeneratedPackages] =
    for {
      types <- api.getTypes.toList.map {
        case objectType: ObjectType => objectTypeSource(objectType, params, api).map(Some(_))
        case _                      => IO(None)
      }.sequence
      packages = types.flatten.foldLeft(GeneratedPackages())(_ addSource _)
    } yield packages

  private def generatePackageObject(params: ModelGenParams): IO[GeneratedFile] = for {
    packageObjectFile <- IO {
      val file = new File(new File(basePackageFilePath(params)), "package.scala")
      file.delete()
      file.getParentFile.mkdirs()
      file
    }
    packageParts = params.basePackage.split("\\.")
    objectName <- IO.fromOption(packageParts.lastOption)(
      new IllegalArgumentException("invalid package name")
    )
    packageName =
      if (packageParts.size <= 1) None else Some(packageParts.dropRight(1).mkString("."))
    packageObjectWithSource <- IO {
      val packageObject = LibrarySupport.applyPackageObject(
        q"""package object ${Term.Name(objectName)}"""
      )(params.allLibraries)

      val packageStatement = packageName.map(pkg => s"package $pkg\n").getOrElse("")
      (packageObject, s"$packageStatement${packageObject.toString}")
    }
    (theObject, source) = packageObjectWithSource
    _ <- FileUtil.writeToFile(packageObjectFile, source)
  } yield GeneratedFile(
    GeneratedTypeSource("package.scala", theObject, params.basePackage, "", None),
    packageObjectFile
  )

  override def generate(api: Api, params: ModelGenParams): IO[GeneratedModel] = for {
    _             <- FileUtil.deleteRecursively(new File(params.targetDir, params.basePackage))
    packageObject <- generatePackageObject(params)
    packages      <- generatePackages(ApiContext(api), params)
    model         <- writePackages(packages, params)
  } yield model.copy(files = model.files ++ List(packageObject))
}
