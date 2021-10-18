package scraml

import cats.effect.IO
import io.vrap.rmf.raml.model.modules.Api
import io.vrap.rmf.raml.model.types._
import scraml.MetaUtil.{packageTerm, typeFromName}
import scraml.RMFUtil.{getAnnotation, getPackageName}

import java.io.File
import scala.collection.immutable.TreeSet
import scala.meta.{Decl, Defn, Member, Pkg, Stat, Term, Type}
import scala.reflect.ClassTag

trait JsonSupport { self: LibrarySupport =>
  // Json support should usually be one of the first to be applied
  // but should still be customizable if you know what you are doing
  override def order: Double = 0.2

  def jsonType: String
}

final case class DefaultTypes(
    array: String = "scala.collection.immutable.List",
    boolean: String = "Boolean",
    date: String = "java.time.LocalDate",
    dateTime: String = "java.time.LocalDateTime",
    double: String = "Double",
    float: String = "Float",
    integer: String = "Int",
    long: String = "Long",
    number: String = "Float",
    string: String = "String",
    time: String = "java.time.LocalTime"
)

final case class ModelGenParams(
    raml: File,
    targetDir: File,
    basePackage: String,
    defaultTypes: DefaultTypes,
    librarySupport: Set[LibrarySupport],
    formatConfig: Option[File] = None,
    generateDateCreated: Boolean = false
) {
  lazy val allLibraries: List[LibrarySupport] = librarySupport.toList.sorted
}

final case class GeneratedModel(sourceFiles: Seq[GeneratedFile], packageObject: GeneratedFile) {
  def files: Seq[GeneratedFile] = sourceFiles ++ List(packageObject)

  override def toString: String = {
    files
      .map(generatedFile => s"${generatedFile.source.name} (${generatedFile.file.getPath})")
      .mkString("\n")
  }
}

final case class ApiContext(private val api: Api) {
  import scala.jdk.CollectionConverters._

  def getTypes: Iterator[AnyType] = api.getTypes.asScala.iterator

  lazy val typesByName: Map[String, AnyType] = {
    api.getTypes.asScala.map(aType => (aType.getName, aType))
  }.toMap

  lazy val scalaExtends: Map[String, AnyType] =
    api.getTypes.asScala.flatMap { aType =>
      for {
        annotation <- getAnnotation(aType)("scala-extends")
        anyType    <- typesByName.get(annotation.getValue.getValue.toString)
      } yield (aType.getName, anyType)
    }.toMap
}

final case class MapTypeSpec(
    keyType: Type,
    valueType: Type,
    singleValue: Boolean = false,
    optional: Boolean = false
)

final case class ModelGenContext(
    packageName: String,
    objectType: ObjectType,
    params: ModelGenParams,
    api: ApiContext,
    apiBaseType: Option[AnyType] = None,
    extendType: Option[Type] = None
) {
  import RMFUtil.anyTypeOrdering

  lazy val scalaBaseType: Option[TypeRef] =
    apiBaseType.flatMap(scalaTypeRef(_, false))

  lazy val anyTypeName: String = params.allLibraries
    .collectFirst { case jsonSupport: JsonSupport =>
      jsonSupport.jsonType
    }
    .getOrElse("Any")

  lazy val scalaExtends: TreeSet[AnyType] = api.scalaExtends
    .foldLeft(TreeSet.empty[AnyType]) { case (acc, (key, typeValue)) =>
      if (typeValue.getName == objectType.getName)
        api.typesByName.get(key).map(acc + _).getOrElse(acc)
      else acc
    }

  lazy val getDirectSubTypes: Set[AnyType] =
    RMFUtil.subTypes(objectType) ++ scalaExtends

  lazy val leafTypes: TreeSet[AnyType] =
    RMFUtil.leafTypes(objectType)

  lazy val typeProperties: Seq[Property] = RMFUtil.typeProperties(objectType).toSeq
  lazy val isSealed: Boolean = getDirectSubTypes.forall(getPackageName(_).contains(packageName))
  lazy val isMapType: Option[MapTypeSpec] = ModelGen.isMapType(objectType, anyTypeName)(this)
  lazy val isSingleton: Boolean           = isMapType.isEmpty && typeProperties.isEmpty

  def isLibraryEnabled[A <: LibrarySupport: ClassTag](): Boolean =
    params.allLibraries.exists(ls =>
      implicitly[ClassTag[A]].runtimeClass.isAssignableFrom(ls.getClass)
    )

  def scalaTypeRef(
      apiType: AnyType,
      optional: Boolean
  ): Option[TypeRef] =
    ModelGen.scalaTypeRef(apiType, optional, None, anyTypeName)(this)

  def scalaTypeRef(
      apiType: AnyType,
      optional: Boolean,
      typeName: Option[String] = None,
      defaultAnyTypeName: String
  ): Option[TypeRef] =
    ModelGen.scalaTypeRef(apiType, optional, typeName, defaultAnyTypeName)(this)

  def typeParams: List[Term.Param] = isMapType match {
    case Some(mapTypeSpec) =>
      val mapApply = Type.Apply(
        Type.Name("Map"),
        List(mapTypeSpec.keyType, mapTypeSpec.valueType)
      )

      val finalType = if (mapTypeSpec.optional) {
        Type.Apply(
          Type.Name("Option"),
          List(mapApply)
        )
      } else mapApply

      List(
        Term.Param(
          Nil,
          Term.Name("values"),
          Some(
            finalType
          ),
          if (mapTypeSpec.optional) Some(Term.Name("None")) else None
        )
      )
    case None => typeProperties.flatMap(ModelGen.scalaProperty(_)(this.anyTypeName)(this)).toList
  }
}

final case class DefnWithCompanion[T <: Defn with Member](defn: T, companion: Option[Defn.Object])

trait LibrarySupport {
  // number between 0 and 1 to define the order of library support applications
  def order: Double = 0.5

  object HasAnyProperties {
    def unapply(defn: Defn.Class): Boolean =
      defn.ctor.paramss.exists(_.nonEmpty)

    def unapply(defn: Defn.Trait): Boolean =
      defn.templ.stats.exists {
        // a declaration without parameters is considered a property
        case prop: Decl.Def if prop.paramss.isEmpty => true
        case _                                      => false
      }
  }

  abstract class HasProperties(names: Seq[String]) {
    final def unapply(defn: Defn.Class): Boolean =
      names.forall(n => defn.ctor.paramss.head.exists(_.name.value == n))
  }

  def modifyClass(classDef: Defn.Class, companion: Option[Defn.Object])(implicit
      context: ModelGenContext
  ): DefnWithCompanion[Defn.Class] =
    DefnWithCompanion(classDef, companion)

  def modifyObject(objectDef: Defn.Object)(implicit
      context: ModelGenContext
  ): DefnWithCompanion[Defn.Object] =
    DefnWithCompanion(objectDef, None)

  def modifyTrait(traitDef: Defn.Trait, companion: Option[Defn.Object])(implicit
      context: ModelGenContext
  ): DefnWithCompanion[Defn.Trait] =
    DefnWithCompanion(traitDef, companion)

  def modifyPackageObject(libs: List[LibrarySupport], api: Api)(implicit
      context: ModelGenContext
  ): Pkg.Object => Pkg.Object = identity

  def modifyEnum(
      enumType: StringType
  )(enumTrait: Defn.Trait, companion: Option[Defn.Object]): DefnWithCompanion[Defn.Trait] =
    DefnWithCompanion(enumTrait, companion)

  final protected def generatePropertiesCode(defn: Defn.Class)(
      f: Term.Param => List[Stat]
  ): List[Stat] =
    defn.ctor.paramss.flatMap(_.flatMap(f))

  final protected def generatePropertiesCode(
      defn: Defn.Trait
  )(f: Decl.Def => List[Stat]): List[Stat] =
    defn.templ.stats
      .collect {
        case prop: Decl.Def if prop.paramss.isEmpty => prop
      }
      .flatMap(f)
}

object LibrarySupport {
  implicit val ordering: Ordering[LibrarySupport] =
    (x: LibrarySupport, y: LibrarySupport) => x.order.compare(y.order)

  def applyClass(
      defn: Defn.Class,
      companion: Option[Defn.Object]
  )(libs: List[LibrarySupport], context: ModelGenContext): DefnWithCompanion[Defn.Class] =
    libs.foldLeft(DefnWithCompanion(defn, companion)) { case (acc, lib) =>
      lib.modifyClass(acc.defn, acc.companion)(context)
    }
  def applyObject(
      defn: Defn.Object
  )(libs: List[LibrarySupport], context: ModelGenContext): DefnWithCompanion[Defn.Object] =
    libs.foldLeft(DefnWithCompanion(defn, None)) { case (acc, lib) =>
      lib.modifyObject(acc.defn)(context)
    }
  def applyTrait(
      defn: Defn.Trait,
      companion: Option[Defn.Object]
  )(libs: List[LibrarySupport], context: ModelGenContext): DefnWithCompanion[Defn.Trait] =
    libs.foldLeft(DefnWithCompanion(defn, companion)) { case (acc, lib) =>
      lib.modifyTrait(acc.defn, acc.companion)(context)
    }
  def applyPackageObject(
      packageObject: Pkg.Object
  )(libs: List[LibrarySupport], context: ModelGenContext, api: Api): Pkg.Object =
    libs.foldLeft(packageObject: Pkg.Object) { case (acc, lib) =>
      lib.modifyPackageObject(libs, api)(context)(acc)
    }
  def applyEnum(enumType: StringType)(enumTrait: Defn.Trait, companion: Defn.Object)(
      libs: List[LibrarySupport]
  ): DefnWithCompanion[Defn.Trait] =
    libs.foldLeft(DefnWithCompanion(enumTrait, Some(companion))) { case (acc, lib) =>
      lib.modifyEnum(enumType)(acc.defn, acc.companion)
    }

  def appendObjectStats(defn: Defn.Object, stats: List[Stat]): Defn.Object =
    defn.copy(templ = defn.templ.copy(stats = defn.templ.stats ++ stats))
  def appendPkgObjectStats(packageObject: Pkg.Object, stats: List[Stat]): Pkg.Object =
    packageObject.copy(templ = packageObject.templ.copy(stats = packageObject.templ.stats ++ stats))
  def appendClassStats(defn: Defn.Class, stats: List[Stat]): Defn.Class =
    defn.copy(templ = defn.templ.copy(stats = defn.templ.stats ++ stats))
  def appendTraitStats(defn: Defn.Trait, stats: List[Stat]): Defn.Trait =
    defn.copy(templ = defn.templ.copy(stats = defn.templ.stats ++ stats))
}

trait ModelGen {
  def generate(api: Api, params: ModelGenParams): IO[GeneratedModel]
}

object ModelGen {
  import scala.jdk.CollectionConverters._

  private def numberTypeString(numberType: NumberType)(implicit
      context: ModelGenContext
  ): String = numberType.getFormat match {
    case NumberFormat.INT64 | NumberFormat.LONG => context.params.defaultTypes.long
    case NumberFormat.FLOAT                     => context.params.defaultTypes.float
    case NumberFormat.DOUBLE                    => context.params.defaultTypes.double
    case _                                      => context.params.defaultTypes.integer
  }

  private case class TypeRefDetails(
      baseType: Type,
      packageName: Option[String] = None,
      defaultValue: Option[Term] = None
  )

  def scalaTypeRef(
      apiType: AnyType,
      optional: Boolean,
      typeName: Option[String] = None,
      defaultAnyTypeName: String
  )(implicit context: ModelGenContext): Option[TypeRef] = {
    def overrideTypeOr(anyType: AnyType, default: => String) = {
      val typeOverride = getAnnotation(anyType)("scala-type")
        .map(_.getValue.getValue.toString)
        .map(MetaUtil.typeFromName)
      TypeRefDetails(typeOverride.getOrElse(typeFromName(default)))
    }

    lazy val mappedType = apiType match {
      case boolean: BooleanType =>
        overrideTypeOr(boolean, context.params.defaultTypes.boolean)
      case integer: IntegerType =>
        overrideTypeOr(integer, context.params.defaultTypes.integer)
      case number: NumberType =>
        overrideTypeOr(number, numberTypeString(number))
      // only use enum type names on top-level defined enums
      // we would need to generate types for property types otherwise
      case _: StringType if apiType.eContainer().eClass().getName == "Property" =>
        TypeRefDetails(Type.Name("String"))
      case stringEnum: StringType
          if Option(stringEnum.getEnum).forall(!_.isEmpty) && stringEnum.getName != "string" =>
        TypeRefDetails(Type.Name(stringEnum.getName))
      case string: StringType =>
        overrideTypeOr(string, context.params.defaultTypes.string)
      case array: ArrayType =>
        val arrayType = getAnnotation(array)("scala-array-type")
          .map(_.getValue.getValue.toString)
          .getOrElse(context.params.defaultTypes.array)
        val itemTypeOverride = getAnnotation(array)("scala-type").map(_.getValue.getValue.toString)
        // we do not need to be optional inside an collection, hence setting it to false
        TypeRefDetails(
          Type.Apply(
            typeFromName(arrayType),
            List(
              scalaTypeRef(array.getItems, false, itemTypeOverride, defaultAnyTypeName).map(
                _.scalaType
              )
            ).flatten
          ),
          None,
          Some(Term.Select(packageTerm(arrayType), Term.Name("empty")))
        )
      case objectType: ObjectType if objectType.getName != "object" =>
        TypeRefDetails(Type.Name(objectType.getName), getPackageName(objectType), None)
      case union: UnionType =>
        TypeRefDetails(
          Type.Apply(
            Type.Name("Either"),
            union.getOneOf.asScala
              .flatMap(scalaTypeRef(_, false, None, defaultAnyTypeName))
              .map(_.scalaType)
              .toList
          )
        )
      case dateTime: DateTimeType =>
        overrideTypeOr(dateTime, context.params.defaultTypes.dateTime)
      case date: DateOnlyType =>
        overrideTypeOr(date, context.params.defaultTypes.date)
      case time: TimeOnlyType =>
        overrideTypeOr(time, context.params.defaultTypes.time)
      case _ => TypeRefDetails(typeFromName(defaultAnyTypeName))
    }

    val typeRef = typeName match {
      case Some(scalaTypeName) => TypeRefDetails(typeFromName(scalaTypeName), None, None)
      case None                => mappedType
    }

    if (optional) {
      Some(
        TypeRef(
          Type.Apply(Type.Name("Option"), List(typeRef.baseType)),
          typeRef.packageName,
          Some(Term.Name("None"))
        )
      )
    } else Some(TypeRef(typeRef.baseType, typeRef.packageName, typeRef.defaultValue))
  }

  def scalaProperty(prop: TypedElement)(fallbackType: String)(implicit
      context: ModelGenContext
  ): Option[Term.Param] = {
    lazy val optional = !prop.getRequired
    val scalaTypeAnnotation =
      Option(prop.getAnnotation("scala-type")).map(_.getValue.getValue.toString)

    ModelGen
      .scalaTypeRef(prop.getType, optional, scalaTypeAnnotation, fallbackType)
      .map(ref => Term.Param(Nil, Term.Name(prop.getName), Some(ref.scalaType), ref.defaultValue))
  }

  /** map type refs from the 'asMap' annotation to real scala types */
  private def mapTypeToScala(anyTypeName: String): String => Type.Ref = {
    case "string" => Type.Name("String")
    case "any"    => typeFromName(anyTypeName)
  }

  def isSingleton(objectType: ObjectType, anyTypeName: String)(implicit
      context: ModelGenContext
  ): Boolean =
    ModelGen.isMapType(objectType, anyTypeName).isEmpty &&
      RMFUtil.typeProperties(objectType).isEmpty

  def isMapType(objectType: ObjectType, anyTypeName: String)(implicit
      context: ModelGenContext
  ): Option[MapTypeSpec] = {
    getAnnotation(objectType)("asMap").map(_.getValue) match {
      case Some(asMap: ObjectInstance) =>
        val properties = asMap.getValue.asScala
        for {
          keyType   <- properties.find(_.getName == "key").map(_.getValue.getValue.toString)
          valueType <- properties.find(_.getName == "value").map(_.getValue.getValue.toString)
        } yield MapTypeSpec(
          mapTypeToScala(anyTypeName)(keyType),
          mapTypeToScala(anyTypeName)(valueType)
        )

      case _ =>
        RMFUtil
          .typeProperties(objectType)
          .filter(prop => Option(prop.getPattern).isDefined)
          .flatMap { prop =>
            lazy val isSingle = prop.getName != "//"
            lazy val optional = !prop.getRequired
            val scalaTypeAnnotation =
              Option(prop.getAnnotation("scala-type")).map(_.getValue.getValue.toString)

            val scalaMapRequired =
              Option(prop.getAnnotation("scala-map-required"))
                .forall(_.getValue.getValue.toString.toBoolean)

            ModelGen.scalaTypeRef(prop.getType, optional, scalaTypeAnnotation, anyTypeName).map {
              valueType =>
                MapTypeSpec(Type.Name("String"), valueType.scalaType, isSingle, !scalaMapRequired)
            }
          }
          .find(_ => true)
    }
  }

}

object ModelGenRunner {
  def run(generator: ModelGen)(params: ModelGenParams): IO[GeneratedModel] = for {
    api   <- RMFUtil.readModel(params.raml)
    model <- generator.generate(api, params)
  } yield model
}
