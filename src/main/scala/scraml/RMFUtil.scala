package scraml

import cats.effect.IO
import io.vrap.rmf.raml.model.RamlModelBuilder
import io.vrap.rmf.raml.model.modules.Api
import io.vrap.rmf.raml.model.types.{Annotation, AnyType, ObjectType, Property}
import org.eclipse.emf.common.util.URI

import java.io.File
import scala.collection.immutable.TreeSet

object RMFUtil {
  import scala.jdk.CollectionConverters._

  def getPackageName(anyType: AnyType): Option[String] =
    getAnnotation(anyType)("package").map(_.getValue.getValue.toString.toLowerCase)

  def getAnnotation(from: AnyType)(name: String): Option[Annotation] = Option(
    from.getAnnotation(name)
  )

  implicit val anyTypeOrdering: Ordering[AnyType] = new Ordering[AnyType] {
    override def compare(x: AnyType, y: AnyType): Int =
      x.getName.compareTo(y.getName)
  }

  def subTypes(aType: AnyType): TreeSet[AnyType] =
    TreeSet(aType.getSubTypes.asScala.filter(_.getName != aType.getName): _*)

  def leafTypes(aType: AnyType): TreeSet[AnyType] =
    subTypes(aType).foldLeft(TreeSet.empty[AnyType]) {
      case (acc, subType) if subType.getSubTypes.isEmpty => (acc + subType)
      case (acc, subType)                                => acc ++ leafTypes(subType)
    }

  def discriminators(aType: AnyType): List[String] = aType match {
    case objectType: ObjectType =>
      Option(objectType.getDiscriminator).toList ++ Option(aType.getType)
        .map(discriminators)
        .getOrElse(List.empty)
    case _ => List.empty
  }

  /** get all (including inherited) properties of a type note: will not include properties from
    * 'scala-extends' references
    */
  def typeProperties(objectType: ObjectType): Iterator[Property] =
    objectType.getAllProperties.asScala.iterator.filter(property =>
      !discriminators(objectType).contains(property.getName)
    )

  def readModel(apiPath: File): IO[Api] = for {
    model <- IO {
      new RamlModelBuilder().buildApi(URI.createFileURI(apiPath.getAbsolutePath))
    }

    api <-
      if (model.getValidationResults.isEmpty) {
        IO.pure(model.getRootObject)
      } else
        IO.raiseError(
          new IllegalArgumentException(s"error while reading model: ${model.getValidationResults}")
        )
  } yield api
}
