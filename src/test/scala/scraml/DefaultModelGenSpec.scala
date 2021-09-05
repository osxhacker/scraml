package scraml

import cats.effect.unsafe.implicits.global
import io.vrap.rmf.raml.model.types.ObjectType
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.File
import scala.meta.Term

class DefaultModelGenSpec extends AnyFlatSpec with Matchers {
  "Default model gen" should "generate data type from API spec" in {
    val params = ModelGenParams(
      new File("src/sbt-test/sbt-scraml/simple/api/simple.raml"),
      new File("target/scraml-test"),
      "scraml",
      Set.empty,
      None
    )

    val generated = ModelGenRunner.run(DefaultModelGen)(params).unsafeRunSync()

    generated.files match {
      case baseType :: dataType :: emptyBase :: noProps :: enumType :: packageObject :: Nil =>
        baseType.source.packageName should be("datatypes")
        baseType.source.source.toString() should be(
          "sealed trait BaseType extends Any { def id: String }"
        )
        baseType.source.companion.map(_.toString()) should be(
          Some(s"""object BaseType""".stripMargin)
        )

        baseType.source.name should be("BaseType")
        baseType.file.getPath should be("target/scraml-test/scraml/datatypes.scala")

        dataType.source.packageName should be("datatypes")
        dataType.source.source.toString() should be(
          "final case class DataType(id: String, foo: Option[String] = None, customTypeProp: scala.math.BigDecimal, customArrayTypeProp: Vector[scala.math.BigDecimal] = Vector.empty) extends BaseType"
        )
        dataType.source.name should be("DataType")
        dataType.file.getPath should be("target/scraml-test/scraml/datatypes.scala")

        emptyBase.source.source.toString() should be("sealed trait EmptyBase")
        noProps.source.source.toString() should be(
          s"""case object NoProps extends EmptyBase""".stripMargin
        )

        enumType.source.source.toString() should be("sealed trait SomeEnum")
        enumType.source.companion.map(_.toString()) should be(Some(s"""object SomeEnum {
             |  case object A extends SomeEnum
             |  case object B extends SomeEnum
             |}""".stripMargin))

        packageObject.source.source.toString should be("package object scraml")

      case _ => fail()
    }
  }

  it should "create a package from string" in {
    MetaUtil.packageTerm("a").toString() should be("a")
    MetaUtil.packageTerm("a.b").toString() should be("a.b")
    MetaUtil.packageTerm("a.b.c").toString() should be("a.b.c")
    MetaUtil.packageTerm("a.b.c.d.e").toString() should be(
      Term
        .Select(
          Term.Select(
            Term.Select(Term.Select(Term.Name("a"), Term.Name("b")), Term.Name("c")),
            Term.Name("d")
          ),
          Term.Name("e")
        )
        .toString()
    )
  }

  it should "create a type from string" in {
    MetaUtil.typeFromName("a").toString() should be("a")
    MetaUtil.typeFromName("a.b").toString() should be("a.b")
    MetaUtil.typeFromName("a.b.c").toString() should be("a.b.c")
    MetaUtil.typeFromName("a.b.c.d.e").toString() should be("a.b.c.d.e")
  }

  it should "generate map types" in {
    val params = ModelGenParams(
      new File(getClass.getClassLoader.getResource("maptype/maptype.raml").toURI),
      new File("target/scraml-maptype-test"),
      "scraml",
      Set.empty,
      None
    )

    val generated = ModelGenRunner.run(DefaultModelGen)(params).unsafeRunSync()

    generated.files.toList match {
      case someMapType :: someMapTypeOpt :: _ :: Nil =>
        someMapType.source.source.toString() should be(
          "final case class SomeMapType(values: Map[String, String])"
        )
        someMapTypeOpt.source.source.toString() should be(
          "final case class SomeMapTypeOpt(values: Option[Map[String, String]] = None)"
        )
      case _ => fail()
    }
  }
}
