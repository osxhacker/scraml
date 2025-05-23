package scraml.libs

import scraml.LibrarySupport.appendObjectStats
import scraml.{DefnWithCompanion, LibrarySupport, ModelGenContext}
import scala.meta._

object CatsShowSupport extends LibrarySupport {
  override def modifyClass(classDef: Defn.Class, companion: Option[Defn.Object])(implicit
      context: ModelGenContext
  ): DefnWithCompanion[Defn.Class] =
    DefnWithCompanion(
      classDef,
      companion.map(appendObjectStats(_, generateShow(classDef)))
    )

  override def modifyTrait(traitDef: Defn.Trait, companion: Option[Defn.Object])(implicit
      context: ModelGenContext
  ): DefnWithCompanion[Defn.Trait] =
    traitDef match {
      case HasAnyProperties() =>
        DefnWithCompanion(traitDef, companion.map(appendObjectStats(_, generateShow(traitDef))))
      case _ => super.modifyTrait(traitDef, companion)(context)
    }

  private def generateShow(classDef: Defn.Class)(implicit
      context: ModelGenContext
  ): List[Stat] =
    q"""
      import cats.Show
      implicit val ${Pat
        .Var(Term.Name(classDef.name.value + "Show"))}: Show[${classDef.name}] = Show.show {
        instance =>
          val buffer = new StringBuilder(${classDef.name.value})
          buffer.append(':')
          buffer.append('\n')

          ..${generatePropertiesCode(classDef) {
        case NamedProperty(param, _, declaredName) =>
          List(
            q"""buffer.append('\t')""",
            q"""buffer.append(${Lit.String(declaredName)})""",
            q"""buffer.append(": ")""",
            q"""buffer.append(instance.${Term.Name(param.name.value)})""",
            q"""buffer.append('\n')"""
          )

        case _ =>
          List.empty
      }}

          ..${context.params.fieldMatchPolicy
        .additionalProperties(context.objectType)(context)
        .map { ap =>
          List(
            q"""buffer.append('\t')""",
            q"""buffer.append(${ap.propertyName})""",
            q"""buffer.append(": ")""",
            q"""buffer.append(instance.${Term.Name(ap.propertyName)})""",
            q"""buffer.append('\n')"""
          )
        }
        .getOrElse(Nil)}
          buffer.toString()
      }""".stats

  private def generateShow(traitDef: Defn.Trait): List[Stat] =
    q"""
      import cats.Show
      implicit val ${Pat
        .Var(Term.Name(traitDef.name.value + "Show"))}: Show[${traitDef.name}] = Show.show {
        instance =>
          val buffer = new StringBuilder(${traitDef.name.value})
          buffer.append(':')
          buffer.append('\n')

          ..${generatePropertiesCode(traitDef) { p =>
        List(
          q"""buffer.append('\t')""",
          q"""buffer.append(${p.name.value})""",
          q"""buffer.append(": ")""",
          q"""buffer.append(instance.${Term.Name(p.name.value)})""",
          q"""buffer.append('\n')"""
        )
      }}

          buffer.toString()
      }""".stats
}
