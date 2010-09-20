package reaktor.scct

import tools.nsc.plugins.{PluginComponent, Plugin}
import java.io.File
import tools.nsc.transform.{Transform, TypingTransformers}
import tools.nsc.symtab.Flags
import tools.nsc.{Phase, Global}

class ScctInstrumentPlugin(val global: Global) extends Plugin {
  val name = "scct"
  val description = "Scala code coverage instrumentation plugin."
  val runsAfter = "refchecks"
  val components = List(new ScctTransformComponent(global))
}

class ScctTransformComponent(val global: Global) extends PluginComponent with TypingTransformers with Transform {
  import global._
  import global.definitions._
  import posAssigner.atPos
  val runsAfter = "refchecks"
  val phaseName = "scctInstrumentation"
  def newTransformer(unit: CompilationUnit) = new Instrumenter(unit)

  var debug = false
  var saveData = true  
  var counter = 0L
  var data: List[CoveredBlock] = Nil
  lazy val coverageFile = new File(global.settings.outdir.value, "coverage.data")
  def newId = { counter += 1; counter.toString }

  override def newPhase(prev: scala.tools.nsc.Phase): StdPhase = new Phase(prev) {
    override def run {
      clearMetadata
      super.run
      saveMetadata
    }
    private def clearMetadata {
      if (coverageFile.exists) coverageFile.delete
    }
    private def saveMetadata {
      if (saveData) {
        println("scct: Saving coverage data.")
        MetadataPickler.toFile(data, coverageFile)
      }
    }
  }

  class Instrumenter(unit: CompilationUnit) extends TypingTransformer(unit) {

    override def transformUnit(unit: CompilationUnit) {
      if (debug) treeBrowser.browse(List(unit))
      registerClasses(unit.body)
      super.transformUnit(unit)
      if (debug) treeBrowser.browse(List(unit))
    }

    override def transform(tree: Tree) = {
      val (continue, result) = preprocess(tree)
      if (continue) super.transform(result) else result
    }

    private def hasSkipAnnotation(md: MemberDef) = md.mods.annotations.exists(_.tpe.safeToString == classOf[uncovered].getName)
    private def isSynthetic(t: Tree) = t.hasSymbol && t.symbol.hasFlag(Flags.SYNTHETIC) && !t.symbol.isAnonymousFunction
    private def isObjectOrTraitConstructor(s: Symbol) = s.isConstructor && (currentClass.isModuleClass || currentClass.isTrait)
    private def isGeneratedMethod(t: DefDef) = !t.symbol.isConstructor && t.pos.offset == currentClass.pos.offset
    private def isAbstractMethod(t: DefDef) = t.symbol.isDeferred
    private def isNonLazyStableMethodOrAccessor(t: DefDef) = !t.symbol.hasFlag(Flags.LAZY) && (t.symbol.hasFlag(Flags.STABLE | Flags.ACCESSOR | Flags.PARAMACCESSOR))
    private def isInAnonymousClass = currentClass.isAnonymousClass

    def preprocess(t: Tree): Tuple2[Boolean, Tree] = t match {
      case _ if isSynthetic(t) => (false, t)
      case md: MemberDef if hasSkipAnnotation(md) => (false, t)
      case dd: DefDef if isNonLazyStableMethodOrAccessor(dd) => (false, t)
      case dd: DefDef if isAbstractMethod(dd) => (false, t)
      case dd: DefDef if isObjectOrTraitConstructor(t.symbol) => (false, t)
      case dd: DefDef if isGeneratedMethod(dd) => (false, t)
      case dd: DefDef if isInAnonymousClass => (false, t)

      case dd: DefDef if (t.symbol.isConstructor) => {
        (false, instrumentConstructor(t.symbol.isPrimaryConstructor, dd))
      }
      case dd @ DefDef(_,_,_,_,_,b @ Block(List(a @ Assign(lhs,rhs)), _)) if (t.symbol.hasFlag(Flags.LAZY)) => {
        val newAssign = copy.Assign(a, lhs, recurse(rhs))
        val newBlock = copy.Block(b, List(newAssign), b.expr)
        (false, copy.DefDef(t, dd.mods, dd.name, dd.tparams, dd.vparamss, dd.tpt, newBlock))
      }
      case dd: DefDef => {
        (false, copy.DefDef(t, dd.mods, dd.name, dd.tparams, dd.vparamss, dd.tpt, recurse(dd.rhs)))
      }
      case vd: ValDef if (vd.symbol.hasFlag(Flags.ACCESSOR | Flags.PARAMACCESSOR)) => {
        (false, t)
      }
      case vd: ValDef => {
        (false, copy.ValDef(t, vd.mods, vd.name, vd.tpt, recurse(vd.rhs)))
      }
      case Template(parents, self, body) => {
        (false, copy.Template(t, parents, self, instrument(super.transformStats(body, t.symbol))))
      }
      case If(cond, thenp, elsep) => {
        (false, copy.If(t, recurse(cond), recurse(thenp), recurse(elsep)))
      }
      case Function(vparams, body) => {
        (false, copy.Function(t, vparams, recurse(body)))
      }
      case Match(selector, cases) => {
        (false, copy.Match(t, recurse(selector), super.transformCaseDefs(cases)))
      }
      case CaseDef(pat, guard, body) => {
        (false, copy.CaseDef(t, pat, recurse(guard), recurse(body)))
      }
      case Try(block, catches, finalizer) => {
        (false, copy.Try(t, recurse(block), super.transformCaseDefs(catches), recurse(finalizer)))
      }
      case LabelDef(name1, List(), i @ If(_, b @ Block(_, Apply(Ident(name2), List())), Literal(Constant(())))) if (name1 == name2 && name1.startsWith("while")) => {
        val newBlock = copy.Block(b, instrument(super.transformStats(b.stats, currentOwner)), b.expr)
        val newIf = copy.If(i, recurse(i.cond), newBlock, i.elsep)
        (false, copy.LabelDef(t, name1, List(), newIf))
      }
      case LabelDef(name1, List(), b @ Block(stats, i @ If(cond, Apply(Ident(name2), List()), Literal(Constant(()))))) if (name1 == name2 && name1.startsWith("doWhile")) => {
        val newIf = copy.If(i, recurse(i.cond), i.thenp, i.elsep)
        val newBlock = copy.Block(b, instrument(super.transformStats(b.stats, currentOwner)), newIf)
        (false, copy.LabelDef(t, name1, List(), newBlock))
      }
      case b: Block => {
        val originalStats = instrument(super.transformStats(b.stats, currentOwner))
        val stats = originalStats ::: (if (shouldInstrument(b.expr)) List(coverageCall(b.expr)) else List())
        val expr = transform(b.expr)
        (false, copy.Block(b, stats, expr))
      }
      case _ => (true, t)
    }

    def recurse(t: Tree) = if (shouldInstrument(t)) instrument(transform(t)) else transform(t)

    def shouldInstrument(t: Tree) = t match {
      case _:ClassDef => false
      case _:Template => false
      case _:TypeDef => false
      case _:DefDef => false
      case _:ValDef => false
      case _:Block => false
      case _:If => false
      case _:Function => false
      case _:Match => false
      case _:CaseDef => false
      case _:Try => false
      case _:LabelDef => false
      case EmptyTree => false
      case Literal(Constant(())) => false
      case _ => true
    }

    def instrument(t: Tree): Tree = copy.Block(t, List(coverageCall(t)), t)

    def instrument(statements: List[Tree]): List[Tree] = statements.foldLeft(List[Tree]()) { (list, stat) =>
      if (shouldInstrument(stat)) list ::: List(coverageCall(stat), stat) else list ::: List(stat)
    }

    def instrumentConstructor(primary: Boolean, dd: DefDef) = {
      val block @ Block(list, expr @ Literal(_)) = dd.rhs

      def instrumentConstructorStatements(list: List[Tree], acc: List[Tree]): List[Tree] = {
        list match {
          case Nil => acc.reverse
          case head :: tail => head match {
            case vd: ValDef => {
              instrumentConstructorStatements(tail, recurse(vd) :: acc)
            }
            case a: Apply if !acc.exists(_.isInstanceOf[Apply]) => {
              val applyInstrumentation = if (primary) coverageCall(block) else coverageCall(a)
              val newApply = copy.Apply(a, a.fun, super.transformTrees(a.args))
              instrumentConstructorStatements(tail, applyInstrumentation :: newApply :: acc)
            }
            case _ => {
              val newTail = instrument(super.transformStats(list, currentOwner))
              acc.reverse ::: newTail
            }
          }
        }
      }

      val newStats = instrumentConstructorStatements(list, List[Tree]())
      val newRhs = copy.Block(block, newStats, expr)
      copy.DefDef(dd, dd.mods, dd.name, dd.tparams, dd.vparamss, dd.tpt, newRhs)
    }

    private def coverageCall(tree: Tree) = {
      val id = newId
      data = CoveredBlock(id, createName(currentOwner, tree), minOffset(tree), false) :: data
      fitIntoTree(tree, rawCoverageCall(id))
    }

    private def fitIntoTree(orig: Tree, newTree: Tree) = {
      localTyper.typed(atPos(orig.pos)(newTree))
    }

    private def rawCoverageCall(id: String) = {
      val fun = Select( Select( Select(Ident("reaktor"), newTermName("scct") ), newTermName("Coverage") ), newTermName("invoked") )
      Apply(fun, List(Literal(id)))
    }
  }

  private def createName(owner: Symbol, tree: Tree) =
    Name(tree.pos.source.get.file.file.getAbsolutePath, classType(owner), packageName(tree, owner), className(tree, owner))

  def className(tree: Tree, owner: Symbol): String = {
    def fromSymbol(s: Symbol): String = {
      def parent = s.owner.enclClass
      if (s.isPackageClass) ""
        else if (s.isAnonymousClass) fromSymbol(parent)
        else if (parent.isPackageClass) s.simpleName.toString
        else fromSymbol(parent) + "." + s.simpleName
    }
    tree match {
      case cd: ClassDef => fromSymbol(cd.symbol)
      case _ => fromSymbol(owner.enclClass)
    }
  }

  def packageName(tree: Tree, owner: Symbol): String = tree match {
    case pd: PackageDef if pd.symbol.isEmptyPackage => "<root>"
    case pd: PackageDef => pd.symbol.fullNameString
    case _ => if (owner.isEmptyPackageClass || owner.isEmptyPackage) "<root>"
                else if (owner.isPackage || owner.isPackageClass) owner.fullNameString
                else if (owner.toplevelClass == NoSymbol) "<root>"
                else if (owner.toplevelClass.owner.isEmptyPackageClass) "<root>"
                else owner.toplevelClass.owner.fullNameString
  }


  def classType(s: Symbol) = {
    if (s.isRoot) ClassTypes.Root
      else if (s.isModule || s.isModuleClass) ClassTypes.Object
      else if (s.isTrait) ClassTypes.Trait
      else ClassTypes.Class
  }


  def minOffset(t: Tree) = new MinimumOffsetFinder().offsetFor(t)

  class MinimumOffsetFinder extends Traverser {
    var min = Integer.MAX_VALUE
    override def traverse(t: Tree) {
      t.pos.offset.foreach { curr =>
        if (curr < min) min = curr
      }
      super.traverse(t)
    }

    def offsetFor(t: Tree): Int = {
      min = Integer.MAX_VALUE
      super.apply(t)
      min
    }
  }


  def registerClasses(t: Tree) = new ClassRegisterer().apply(t)

  class ClassRegisterer extends Traverser {
    override def traverse(t: Tree) = {
      t match {
        case cd: ClassDef if (!cd.symbol.hasFlag(Flags.SYNTHETIC)) => {
          data = CoveredBlock(newId, createName(cd.symbol, t), minOffset(t), true) :: data
        }
        case _ =>
      }
      super.traverse(t)
    }
  }
}