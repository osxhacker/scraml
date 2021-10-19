package scraml.libs

import scraml.LibrarySupport.appendObjectStats
import scraml.{DefnWithCompanion, LibrarySupport, ModelGenContext}

import scala.meta._

object CatsEqSupport extends LibrarySupport {
  object IsVersionedEntity extends HasProperties("id" :: "version" :: Nil)

  private def eqStats(classDef: Defn.Class): List[Stat] =
    q"""
      import cats.kernel.Eq
      implicit val ${Pat.Var(Term.Name(classDef.name.value + "Eq"))}: Eq[${classDef.name}] =
        new Eq[${classDef.name}] {
          override def eqv(a: ${classDef.name}, b: ${classDef.name}): Boolean = {
            ${
      val checks = classDef match {
        case IsVersionedEntity() =>
          List[Term](q"a.id.equals(b.id)", q"a.version == b.version")
        case _ =>
          generatePropertiesCode(classDef) { prop =>
            List[Term](q"""a.${Term.Name(prop.name.value)} == b.${Term.Name(prop.name.value)}""")
          }
      }

      checks match {
        case Nil         => q"""a.equals(b)"""
        case head :: Nil => head
        case head :: tail =>
          tail.foldLeft(head) { case (accum: Term, check: Term) =>
            q"""($accum) && ($check)"""
          }
      }
    }
        }
      }
    """.stats

  override def modifyClass(classDef: Defn.Class, companion: Option[Defn.Object])(implicit
      context: ModelGenContext
  ): DefnWithCompanion[Defn.Class] =
    DefnWithCompanion(
      classDef,
      companion.map(appendObjectStats(_, eqStats(classDef)))
    )
}
