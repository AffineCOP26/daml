// Copyright (c) 2021 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.lf
package speedy

import java.util

import com.daml.lf.data.Ref._
import com.daml.lf.data.{FrontStack, ImmArray, Ref, Time}
import com.daml.lf.language.Ast._
import com.daml.lf.language.{Util => AstUtil}
import com.daml.lf.ledger.{Authorize, CheckAuthorizationMode}
import com.daml.lf.speedy.Compiler.{CompilationError, PackageNotFound}
import com.daml.lf.speedy.PartialTransaction.ExercisesContextInfo
import com.daml.lf.speedy.SError._
import com.daml.lf.speedy.SExpr._
import com.daml.lf.speedy.SResult._
import com.daml.lf.speedy.SBuiltin.checkAborted
import com.daml.lf.transaction.{Node, TransactionVersion}
import com.daml.lf.value.{Value => V}
import org.slf4j.LoggerFactory

import scala.annotation.tailrec
import scala.jdk.CollectionConverters._
import scala.util.control.NoStackTrace

private[lf] object Speedy {

  // fake participant to generate a new transactionSeed when running scenarios
  private[this] val scenarioServiceParticipant =
    Ref.ParticipantId.assertFromString("scenario-service")

  // Would like these to have zero cost when not enabled. Better still, to be switchable at runtime.
  private[this] val enableInstrumentation: Boolean = false
  private[this] val enableLightweightStepTracing: Boolean = false

  /** Instrumentation counters. */
  final case class Instrumentation(
      var classifyCounts: Classify.Counts,
      var countPushesKont: Int,
      var countPushesEnv: Int,
      var maxDepthKont: Int,
      var maxDepthEnv: Int,
  ) {
    def print(): Unit = {
      println("--------------------")
      println(s"#steps: ${classifyCounts.steps}")
      println(s"#pushEnv: $countPushesEnv")
      println(s"maxDepthEnv: $maxDepthEnv")
      println(s"#pushKont: $countPushesKont")
      println(s"maxDepthKont: $maxDepthKont")
      println("--------------------")
      println(s"classify:\n${classifyCounts.pp}")
      println("--------------------")
    }
  }

  private object Instrumentation {
    def apply(): Instrumentation = {
      Instrumentation(
        classifyCounts = new Classify.Counts(),
        countPushesKont = 0,
        countPushesEnv = 0,
        maxDepthKont = 0,
        maxDepthEnv = 0,
      )
    }
  }

  /*
   Speedy uses a caller-saves strategy for managing the environment.  In a Speedy machine,
   the environment is represented by the `frame`, `actuals`, and `env` components.

   We use the terminology "frame" for the array of values which correspond to the
   evaluated "free-vars" of a function closure.

   We use the terminology "actuals" for the array of values which correspond to the
   evaluated "args" of a function application. (The args being an array of expressions)

   The environment "env" is now only used for let-bindings and pattern-matches.

   Continuations are responsible for restoring their own frame/actuals.  On the other
   hand, it is the code which executes a continuation which is responsible for ensuring
   the env-stack of temporaries is popped to the correct height, before the continuation
   is itself executed. See popTempStackToBase.

   When we create/push a continuation which requires it's environment to be preserved, we
   record the current frame and actuals within the continuation. In addition, we call
   markBase to allow the continuation access to temporaries on the env-stack: markBase
   shifts envBase to the current env.size, returning the old envBase, allowing the
   continuation to reset the envBase (calling restoreBase) when it is executed.
   */

  private type Frame = Array[SValue]

  private type Actuals = util.ArrayList[SValue]

  private[lf] sealed trait LedgerMode

  private[lf] final case class CachedContract(
      templateId: Ref.TypeConName,
      value: SValue,
      signatories: Set[Party],
      observers: Set[Party],
      key: Option[Node.KeyWithMaintainers[V[Nothing]]],
  )

  private[lf] final case class OnLedger(
      val validating: Boolean,
      /* Controls if authorization checks are performed during evaluation */
      val checkAuthorization: CheckAuthorizationMode,
      /* The current partial transaction */
      var ptx: PartialTransaction,
      /* Committers of the action. */
      var committers: Set[Party],
      /* Commit location, if a scenario commit is in progress. */
      var commitLocation: Option[Location],
      /* Flag to trace usage of get_time builtins */
      var dependsOnTime: Boolean,
      // local contracts, that are contracts created in the current transaction, values are stored in cachedContracts
      var localContracts: Set[V.ContractId],
      // global contract discriminators, that are discriminators from contract created in previous transactions
      var globalDiscriminators: Set[crypto.Hash],
      var cachedContracts: Map[V.ContractId, CachedContract],
  ) extends LedgerMode

  private[lf] final case object OffLedger extends LedgerMode

  /** The speedy CEK machine. */
  final class Machine(
      /* The control is what the machine should be evaluating. If this is not
       * null, then `returnValue` must be null.
       */
      var ctrl: SExpr,
      /* `returnValue` contains the result once the expression in `ctrl` has
       * been fully evaluated. If this is not null, then `ctrl` must be null.
       */
      var returnValue: SValue,
      /* Frame: to access values for a closure's free-vars. */
      var frame: Frame,
      /* Actuals: to access values for a function application's arguments. */
      var actuals: Actuals,
      /* [env] is a stack of temporary values for: let-bindings and pattern-matches. */
      var env: Env,
      /* [envBase] is the depth of the temporaries-stack when the current code-context was
       * begun. We revert to this depth when entering a closure, or returning to the top
       * continuation on the kontStack.
       */
      var envBase: Int,
      /* Kont, or continuation specifies what should be done next
       * once the control has been evaluated.
       */
      var kontStack: util.ArrayList[Kont],
      /* The last encountered location */
      var lastLocation: Option[Location],
      /* The trace log. */
      val traceLog: TraceLog,
      /* Compiled packages (DAML-LF ast + compiled speedy expressions). */
      var compiledPackages: CompiledPackages,
      /* Used when enableLightweightStepTracing is true */
      var steps: Int,
      /* Used when enableInstrumentation is true */
      var track: Instrumentation,
      /* Profile of the run when the packages haven been compiled with profiling enabled. */
      var profile: Profile,
      /* True if we are running on ledger building transactions, false if we
         are running off-ledger code, e.g., DAML Script or
         Triggers. It is safe to use on ledger for off ledger code but
         not the other way around.
       */
      val ledgerMode: LedgerMode,
  ) {

    /* kont manipulation... */

    @inline
    private[speedy] def kontDepth(): Int = kontStack.size()

    private[lf] def withOnLedger[T](op: String)(f: OnLedger => T): T =
      ledgerMode match {
        case onLedger: OnLedger => f(onLedger)
        case OffLedger => throw SRequiresOnLedger(op)
      }

    @inline
    private[speedy] def pushKont(k: Kont): Unit = {
      kontStack.add(k)
      if (enableInstrumentation) {
        track.countPushesKont += 1
        if (kontDepth() > track.maxDepthKont) track.maxDepthKont = kontDepth()
      }
    }

    @inline
    private[speedy] def popKont(): Kont = {
      kontStack.remove(kontStack.size - 1)
    }

    /* env manipulation... */

    // The environment is partitioned into three locations: Stack, Args, Free
    // The run-time location of a variable is determined (at compile time) by closureConvert
    // And made explicit by a specifc speedy expression node: SELocS/SELocA/SELocF
    // At runtime these different location-node execute by calling the corresponding `getEnv*` function

    // Variables which reside on the stack. Indexed (from 1) by relative offset from the top of the stack (1 is top!)
    @inline
    private[speedy] def getEnvStack(i: Int): SValue = env.get(env.size - i)

    // Variables which reside in the args array of the current frame. Indexed by absolute offset.
    @inline
    private[speedy] def getEnvArg(i: Int): SValue = actuals.get(i)

    // Variables which reside in the free-vars array of the current frame. Indexed by absolute offset.
    @inline
    private[speedy] def getEnvFree(i: Int): SValue = frame(i)

    @inline def pushEnv(v: SValue): Unit = {
      env.add(v)
      if (enableInstrumentation) {
        track.countPushesEnv += 1
        if (env.size > track.maxDepthEnv) track.maxDepthEnv = env.size
      }
    }

    // markBase is called when pushing a continuation which requires access to temporaries
    // currently on the env-stack.  After this call, envBase is set to the current
    // env.size. The old envBase is returned so it can be restored later by the caller.
    @inline
    def markBase(): Int = {
      val oldBase = this.envBase
      val newBase = this.env.size
      if (newBase < oldBase) {
        crash(s"markBase: $oldBase -> $newBase -- NOT AN INCREASE")
      }
      this.envBase = newBase
      oldBase
    }

    // restoreBase is called when executing a continuation which previously saved the
    // value of envBase (by calling markBase).
    @inline
    def restoreBase(envBase: Int): Unit = {
      if (this.envBase < envBase) {
        crash(s"restoreBase: ${this.envBase} -> ${envBase} -- NOT A REDUCTION")
      }
      this.envBase = envBase
    }

    // popTempStackToBase is called when we begin a new code-context which does not need
    // to access any temporaries pushed to the stack by the current code-context. This
    // occurs either when returning to the top continuation on the kontStack or when
    // entering (tail-calling) a closure.
    @inline
    def popTempStackToBase(): Unit = {
      val envSizeToBeRestored = this.envBase
      val count = env.size - envSizeToBeRestored
      if (count < 0) {
        crash(s"popTempStackToBase: ${env.size} --> ${envSizeToBeRestored} -- WRONG DIRECTION")
      }
      if (count > 0) {
        env.subList(envSizeToBeRestored, env.size).clear
      }
    }

    @inline
    def restoreFrameAndActuals(frame: Frame, actuals: Actuals): Unit = {
      // Restore the frame and actuals to the state when the continuation was created.
      this.frame = frame
      this.actuals = actuals
    }

    /** Push a single location to the continuation stack for the sake of
      *        maintaining a stack trace.
      */
    def pushLocation(loc: Location): Unit = {
      lastLocation = Some(loc)
      val last_index = kontStack.size() - 1
      val last_kont = if (last_index >= 0) Some(kontStack.get(last_index)) else None
      last_kont match {
        // NOTE(MH): If the top of the continuation stack is the monadic token,
        // we push location information under it to account for the implicit
        // lambda binding the token.
        case Some(KArg(_, Array(SEValue.Token))) => {
          // Can't call pushKont here, because we don't push at the top of the stack.
          kontStack.add(last_index, KLocation(this, loc))
          if (enableInstrumentation) {
            track.countPushesKont += 1
            if (kontDepth() > track.maxDepthKont) track.maxDepthKont = kontDepth()
          }
        }
        // NOTE(MH): When we use a cached top level value, we need to put the
        // stack trace it produced back on the continuation stack to get
        // complete stack trace at the use site. Thus, we store the stack traces
        // of top level values separately during their execution.
        case Some(KCacheVal(machine, v, defn, stack_trace)) =>
          kontStack.set(last_index, KCacheVal(machine, v, defn, loc :: stack_trace)); ()
        case _ => pushKont(KLocation(this, loc))
      }
    }

    /** Push an entire stack trace to the continuation stack. The first
      *        element of the list will be pushed last.
      */
    def pushStackTrace(locs: List[Location]): Unit =
      locs.reverse.foreach(pushLocation)

    /** Compute a stack trace from the locations in the continuation stack.
      *        The last seen location will come last.
      */
    def stackTrace(): ImmArray[Location] = {
      val s = ImmArray.newBuilder[Location]
      kontStack.forEach { k =>
        k match {
          case KLocation(_, location) => s += location
          case _ => ()
        }
      }
      s.result()
    }

    private[lf] def contextActors: Set[Party] =
      withOnLedger("ptx") { onLedger =>
        onLedger.ptx.context.info match {
          case ctx: ExercisesContextInfo =>
            ctx.actingParties union ctx.signatories
          case _ =>
            onLedger.committers
        }
      }

    private[lf] def auth: Option[Authorize] =
      withOnLedger("checkAuthorization") { onLedger =>
        onLedger.checkAuthorization match {
          case CheckAuthorizationMode.On => Some(Authorize(this.contextActors))
          case CheckAuthorizationMode.Off => None
        }
      }

    def addLocalContract(
        coid: V.ContractId,
        templateId: Ref.TypeConName,
        arg: SValue,
        signatories: Set[Party],
        observers: Set[Party],
        key: Option[Node.KeyWithMaintainers[V[Nothing]]],
    ) =
      withOnLedger("addLocalContract") { onLedger =>
        coid match {
          case V.ContractId.V1(discriminator, _)
              if onLedger.globalDiscriminators.contains(discriminator) =>
            crash("Conflicting discriminators between a global and local contract ID.")
          case _ =>
            onLedger.localContracts = onLedger.localContracts + coid
            onLedger.cachedContracts = onLedger.cachedContracts.updated(
              coid,
              CachedContract(templateId, arg, signatories, observers, key),
            )
        }
      }

    def addGlobalCid(cid: V.ContractId) =
      withOnLedger("addGlobalCid") { onLedger =>
        cid match {
          case V.ContractId.V1(discriminator, _) =>
            if (onLedger.localContracts.contains(V.ContractId.V1(discriminator)))
              crash("Conflicting discriminators between a global and local contract ID.")
            else
              onLedger.globalDiscriminators = onLedger.globalDiscriminators + discriminator
          case _ =>
        }
      }

    /** Reuse an existing speedy machine to evaluate a new expression.
      *      Do not use if the machine is partway though an existing evaluation.
      *      i.e. run() has returned an `SResult` requiring a callback.
      */
    def setExpressionToEvaluate(expr: SExpr): Unit = {
      ctrl = expr
      kontStack = initialKontStack()
      env = emptyEnv
      envBase = 0
      steps = 0
      track = Instrumentation()
    }

    /** Run a machine until we get a result: either a final-value or a request for data, with a callback */
    def run(): SResult = {
      // Note: We have an outer and inner while loop.
      // An exception handler is wrapped around the inner-loop, but inside the outer-loop.
      // Most iterations are performed by the inner-loop, thus avoiding the work of to
      // wrap the exception handler on each of these steps. This is a performace gain.
      // However, we still need the outer loop because of the case:
      //    case _:SErrorDamlException if tryHandleSubmitMustFail =>
      // where we must continue iteration.
      var result: SResult = null
      while (result == null) {
        // note: exception handler is outside while loop
        try {
          // normal exit from this loop is when KFinished.execute throws SpeedyHungry
          while (true) {
            if (enableInstrumentation) {
              Classify.classifyMachine(this, track.classifyCounts)
            }
            if (enableLightweightStepTracing) {
              steps += 1
              println(s"$steps: ${PrettyLightweight.ppMachine(this)}")
            }
            if (returnValue != null) {
              val value = returnValue
              returnValue = null
              popTempStackToBase()
              popKont().execute(value)
            } else {
              val expr = ctrl
              ctrl = null
              expr.execute(this)
            }
          }
        } catch {
          case SpeedyHungry(res: SResult) =>
            if (enableInstrumentation) {
              res match {
                case _: SResultFinalValue => track.print()
                case _ => ()
              }
            }
            result = res //stop
          case serr: SError =>
            serr match {
              case _: SErrorDamlException if tryHandleSubmitMustFail() =>
                () // outer loop will run again
              case _ => result = SResultError(serr) //stop
            }
          case ex: RuntimeException =>
            result = SResultError(SErrorCrash(s"exception: $ex")) //stop
        }
      }
      result
    }

    /** Try to handle a DAML exception by looking for
      * the catch handler. Returns true if the exception
      * was caught.
      */
    private[speedy] def tryHandleSubmitMustFail(): Boolean = {
      val catchIndex =
        kontStack.asScala.lastIndexWhere(_.isInstanceOf[KCatchSubmitMustFail])
      if (catchIndex >= 0) {
        val kcatch = kontStack.get(catchIndex).asInstanceOf[KCatchSubmitMustFail]
        kontStack.subList(catchIndex, kontStack.size).clear()
        restoreBase(kcatch.envSize)
        returnValue = SValue.SValue.True
        true
      } else
        false
    }

    def lookupVal(eval: SEVal): Unit = {
      eval.cached match {
        case Some((v, stack_trace)) =>
          pushStackTrace(stack_trace)
          returnValue = v

        case None =>
          val ref = eval.ref
          compiledPackages.getDefinition(ref) match {
            case Some(defn) =>
              defn.cached match {
                case Some((svalue, stackTrace)) =>
                  eval.setCached(svalue, stackTrace)
                  returnValue = svalue
                case None =>
                  pushKont(KCacheVal(this, eval, defn, Nil))
                  ctrl = defn.body
              }
            case None =>
              if (compiledPackages.getSignature(ref.packageId).isDefined)
                crash(
                  s"definition $ref not found even after caller provided new set of packages"
                )
              else
                throw SpeedyHungry(
                  SResultNeedPackage(
                    ref.packageId,
                    { packages =>
                      this.compiledPackages = packages
                      // To avoid infinite loop in case the packages are not updated properly by the caller
                      assert(compiledPackages.getSignature(ref.packageId).isDefined)
                      lookupVal(eval)
                    },
                  )
                )
          }
      }
    }

    /** This function is used to enter an ANF application.  The function has been evaluated to
      *      a value, and so have the arguments - they just need looking up
      */
    // TODO: share common code with executeApplication
    private[speedy] def enterApplication(vfun: SValue, newArgs: Array[SExprAtomic]): Unit = {
      vfun match {
        case SValue.SPAP(prim, actualsSoFar, arity) =>
          val missing = arity - actualsSoFar.size
          val newArgsLimit = Math.min(missing, newArgs.length)

          val actuals = new util.ArrayList[SValue](actualsSoFar.size + newArgsLimit)
          actuals.addAll(actualsSoFar)

          val othersLength = newArgs.length - missing

          // Evaluate the arguments
          var i = 0
          while (i < newArgsLimit) {
            val newArg = newArgs(i)
            val v = newArg.lookupValue(this)
            actuals.add(v)
            i += 1
          }

          // Not enough arguments. Return a PAP.
          if (othersLength < 0) {
            this.returnValue = SValue.SPAP(prim, actuals, arity)

          } else {
            // Too many arguments: Push a continuation to re-apply the over-applied args.
            if (othersLength > 0) {
              val others = new Array[SExprAtomic](othersLength)
              System.arraycopy(newArgs, missing, others, 0, othersLength)
              this.pushKont(KOverApp(this, others))
            }
            // Now the correct number of arguments is ensured. What kind of prim do we have?
            prim match {
              case closure: SValue.PClosure =>
                this.frame = closure.frame
                this.actuals = actuals
                // Maybe push a continuation for the profiler
                val label = closure.label
                if (label != null) {
                  this.profile.addOpenEvent(label)
                  this.pushKont(KLeaveClosure(this, label))
                }
                // Start evaluating the body of the closure.
                popTempStackToBase()
                this.ctrl = closure.expr

              case SValue.PBuiltin(builtin) =>
                this.actuals = actuals
                try {
                  builtin.execute(actuals, this)
                } catch {
                  // We turn arithmetic exceptions into a daml exception that can be caught.
                  case e: ArithmeticException =>
                    throw DamlEArithmeticError(e.getMessage)
                }

            }
          }

        case _ =>
          crash(s"Applying non-PAP: $vfun")
      }
    }

    /** The function has been evaluated to a value, now start evaluating the arguments. */
    private[speedy] def executeApplication(vfun: SValue, newArgs: Array[SExpr]): Unit = {
      vfun match {
        case SValue.SPAP(prim, actualsSoFar, arity) =>
          val missing = arity - actualsSoFar.size
          val newArgsLimit = Math.min(missing, newArgs.length)

          val actuals = new util.ArrayList[SValue](actualsSoFar.size + newArgsLimit)
          actuals.addAll(actualsSoFar)

          val othersLength = newArgs.length - missing

          // Not enough arguments. Push a continuation to construct the PAP.
          if (othersLength < 0) {
            this.pushKont(KPap(this, prim, actuals, arity))
          } else {
            // Too many arguments: Push a continuation to re-apply the over-applied args.
            if (othersLength > 0) {
              val others = new Array[SExpr](othersLength)
              System.arraycopy(newArgs, missing, others, 0, othersLength)
              this.pushKont(KArg(this, others))
            }
            // Now the correct number of arguments is ensured. What kind of prim do we have?
            prim match {
              case closure: SValue.PClosure =>
                // Push a continuation to execute the function body when the arguments have been evaluated
                this.pushKont(KFun(this, closure, actuals))

              case SValue.PBuiltin(builtin) =>
                // Push a continuation to execute the builtin when the arguments have been evaluated
                this.pushKont(KBuiltin(this, builtin, actuals))
            }
          }
          this.evaluateArguments(actuals, newArgs, newArgsLimit)

        case _ =>
          crash(s"Applying non-PAP: $vfun")
      }
    }

    /** Evaluate the first 'n' arguments in 'args'.
      *      'args' will contain at least 'n' expressions, but it may contain more(!)
      *
      *      This is because, in the call from 'executeApplication' below, although over-applied
      *      arguments are pushed into a continuation, they are not removed from the original array
      *      which is passed here as 'args'.
      */
    private[speedy] def evaluateArguments(
        actuals: util.ArrayList[SValue],
        args: Array[SExpr],
        n: Int,
    ) = {
      var i = 1
      while (i < n) {
        val arg = args(n - i)
        this.pushKont(KPushTo(this, actuals, arg))
        i = i + 1
      }
      this.ctrl = args(0)
    }

    private[speedy] def print(count: Int) = {
      println(s"Step: $count")
      if (returnValue != null) {
        println("Control: null")
        println("Return:")
        println(s"  ${returnValue}")
      } else {
        println("Control:")
        println(s"  ${ctrl}")
        println("Return: null")
      }
      println("Environment:")
      env.forEach { v =>
        println("  " + v.toString)
      }
      println("Kontinuation:")
      kontStack.forEach { k =>
        println(s"  " + k.toString)
      }
      println("============================================================")
    }

    // reinitialize the state of the machine with a new fresh submission seed.
    // Should be used only when running scenario
    private[lf] def clearCommit: Unit = withOnLedger("clearCommit") { onLedger =>
      val freshSeed =
        crypto.Hash.deriveTransactionSeed(
          onLedger.ptx.context.nextChildSeed,
          scenarioServiceParticipant,
          onLedger.ptx.submissionTime,
        )
      onLedger.committers = Set.empty
      onLedger.commitLocation = None
      onLedger.ptx = PartialTransaction.initial(
        onLedger.ptx.packageToTransactionVersion,
        onLedger.ptx.submissionTime,
        InitialSeeding.TransactionSeed(freshSeed),
      )
    }

    // This translates a well-typed LF value (typically coming from the ledger)
    // to speedy value and set the control of with the result.
    // Note the method does not check the value is well-typed as opposed as
    // com.daml.lf.engine.preprocessing.ValueTranslator.translateValue.
    // All the contract IDs contained in the value are considered global.
    // Raises an exception if missing a package.
    private[speedy] def importValue(typ0: Type, value0: V[V.ContractId]): Unit = {

      def go(ty: Type, value: V[V.ContractId]): SValue = {
        def typeMismatch = crash(s"mismatching type: $ty and value: $value")

        val (tyFun, argTypes) = AstUtil.destructApp(ty)
        tyFun match {
          case TBuiltin(_) =>
            argTypes match {
              case Nil =>
                value match {
                  case V.ValueInt64(value) =>
                    SValue.SInt64(value)
                  case V.ValueNumeric(value) =>
                    SValue.SNumeric(value)
                  case V.ValueText(value) =>
                    SValue.SText(value)
                  case V.ValueTimestamp(value) =>
                    SValue.STimestamp(value)
                  case V.ValueDate(value) =>
                    SValue.SDate(value)
                  case V.ValueParty(value) =>
                    SValue.SParty(value)
                  case V.ValueBool(b) =>
                    if (b) SValue.SValue.True else SValue.SValue.False
                  case V.ValueUnit =>
                    SValue.SValue.Unit
                  case _ =>
                    typeMismatch
                }
              case elemType :: Nil =>
                value match {
                  case V.ValueContractId(cid) =>
                    addGlobalCid(cid)
                    SValue.SContractId(cid)
                  case V.ValueNumeric(d) =>
                    SValue.SNumeric(d)
                  case V.ValueOptional(mb) =>
                    mb match {
                      case Some(value) => SValue.SOptional(Some(go(elemType, value)))
                      case None => SValue.SValue.None
                    }
                  // list
                  case V.ValueList(ls) =>
                    SValue.SList(ls.map(go(elemType, _)))

                  // textMap
                  case V.ValueTextMap(entries) =>
                    SValue.SGenMap(
                      isTextMap = true,
                      entries = entries.iterator.map { case (k, v) =>
                        SValue.SText(k) -> go(elemType, v)
                      },
                    )
                  case _ =>
                    typeMismatch
                }
              case keyType :: valueType :: Nil =>
                value match {
                  // genMap
                  case V.ValueGenMap(entries) =>
                    SValue.SGenMap(
                      isTextMap = false,
                      entries = entries.iterator.map { case (k, v) =>
                        go(keyType, k) -> go(valueType, v)
                      },
                    )
                  case _ =>
                    typeMismatch
                }
              case _ =>
                typeMismatch
            }
          case TTyCon(tyCon) =>
            val signature = compiledPackages.signatures(tyCon.packageId)
            value match {
              case V.ValueRecord(_, fields) =>
                signature.lookupRecord(tyCon.qualifiedName) match {
                  case Right((params, DataRecord(fieldsDef))) =>
                    lazy val subst = (params.toSeq.view.map(_._1) zip argTypes).toMap
                    val n = fields.length
                    val values = new util.ArrayList[SValue](n)
                    (fieldsDef.iterator zip fields.iterator).foreach {
                      case ((_, fieldType), (_, fieldValue)) =>
                        values.add(go(AstUtil.substitute(fieldType, subst), fieldValue))
                        ()
                    }
                    SValue.SRecord(tyCon, fieldsDef.map(_._1), values)
                  case Left(err) => crash(err)
                }
              case V.ValueVariant(_, constructor, value) =>
                signature.lookupVariant(tyCon.qualifiedName) match {
                  case Right((params, variantDef)) =>
                    val rank = variantDef.constructorRank(constructor)
                    val typDef = variantDef.variants(rank)._2
                    val valType =
                      AstUtil.substitute(typDef, params.toSeq.view.map(_._1) zip argTypes)
                    SValue.SVariant(tyCon, constructor, rank, go(valType, value))
                  case Left(err) => crash(err)
                }
              case V.ValueEnum(_, constructor) =>
                signature.lookupEnum(tyCon.qualifiedName) match {
                  case Right(enumDef) =>
                    val rank = enumDef.constructorRank(constructor)
                    SValue.SEnum(tyCon, constructor, rank)
                  case Left(err) => crash(err)
                }
              case _ =>
                typeMismatch
            }
          case _ =>
            typeMismatch
        }
      }

      returnValue = go(typ0, value0)
    }

  }

  object Machine {

    private val damlTraceLog = LoggerFactory.getLogger("daml.tracelog")

    def apply(
        compiledPackages: CompiledPackages,
        submissionTime: Time.Timestamp,
        initialSeeding: InitialSeeding,
        expr: SExpr,
        globalCids: Set[V.ContractId],
        committers: Set[Party],
        validating: Boolean = false,
        checkAuthorization: CheckAuthorizationMode = CheckAuthorizationMode.On,
        traceLog: TraceLog = RingBufferTraceLog(damlTraceLog, 100),
    ): Machine = {
      val pkg2TxVersion =
        compiledPackages.packageLanguageVersion.andThen(TransactionVersion.assignNodeVersion)
      new Machine(
        ctrl = expr,
        returnValue = null,
        frame = null,
        actuals = null,
        env = emptyEnv,
        envBase = 0,
        kontStack = initialKontStack(),
        lastLocation = None,
        ledgerMode = OnLedger(
          validating = validating,
          checkAuthorization = checkAuthorization,
          ptx = PartialTransaction.initial(pkg2TxVersion, submissionTime, initialSeeding),
          committers = committers,
          commitLocation = None,
          dependsOnTime = false,
          localContracts = Set.empty,
          globalDiscriminators = globalCids.collect { case V.ContractId.V1(discriminator, _) =>
            discriminator
          },
          cachedContracts = Map.empty,
        ),
        traceLog = traceLog,
        compiledPackages = compiledPackages,
        steps = 0,
        track = Instrumentation(),
        profile = new Profile(),
      )
    }

    @throws[PackageNotFound]
    @throws[CompilationError]
    // Construct a machine for running an update expression (testing -- avoiding scenarios)
    def fromUpdateExpr(
        compiledPackages: CompiledPackages,
        transactionSeed: crypto.Hash,
        updateE: Expr,
        committer: Party,
    ): Machine = {
      val updateSE: SExpr = compiledPackages.compiler.unsafeCompile(updateE)
      Machine(
        compiledPackages = compiledPackages,
        submissionTime = Time.Timestamp.MinValue,
        initialSeeding = InitialSeeding.TransactionSeed(transactionSeed),
        expr = SEApp(updateSE, Array(SEValue.Token)),
        globalCids = Set.empty,
        committers = Set(committer),
      )
    }

    @throws[PackageNotFound]
    @throws[CompilationError]
    // Construct a machine for running scenario.
    def fromScenarioSExpr(
        compiledPackages: CompiledPackages,
        transactionSeed: crypto.Hash,
        scenario: SExpr,
    ): Machine = Machine(
      compiledPackages = compiledPackages,
      submissionTime = Time.Timestamp.MinValue,
      initialSeeding = InitialSeeding.TransactionSeed(transactionSeed),
      expr = SEApp(scenario, Array(SEValue.Token)),
      globalCids = Set.empty,
      committers = Set.empty,
    )

    @throws[PackageNotFound]
    @throws[CompilationError]
    // Construct a machine for running scenario.
    def fromScenarioExpr(
        compiledPackages: CompiledPackages,
        transactionSeed: crypto.Hash,
        scenario: Expr,
    ): Machine =
      fromScenarioSExpr(
        compiledPackages = compiledPackages,
        transactionSeed = transactionSeed,
        scenario = compiledPackages.compiler.unsafeCompile(scenario),
      )

    @throws[PackageNotFound]
    @throws[CompilationError]
    // Construct a machine for evaluating an expression that is neither an update nor a scenario expression.
    def fromPureSExpr(
        compiledPackages: CompiledPackages,
        expr: SExpr,
        traceLog: TraceLog = RingBufferTraceLog(damlTraceLog, 100),
    ): Machine =
      new Machine(
        ctrl = expr,
        returnValue = null,
        frame = null,
        actuals = null,
        env = emptyEnv,
        envBase = 0,
        kontStack = initialKontStack(),
        lastLocation = None,
        ledgerMode = OffLedger,
        traceLog = traceLog,
        compiledPackages = compiledPackages,
        steps = 0,
        track = Instrumentation(),
        profile = new Profile(),
      )

    @throws[PackageNotFound]
    @throws[CompilationError]
    // Construct a machine for evaluating an expression that is neither an update nor a scenario expression.
    def fromPureExpr(
        compiledPackages: CompiledPackages,
        expr: Expr,
    ): Machine =
      fromPureSExpr(compiledPackages, compiledPackages.compiler.unsafeCompile(expr))

  }

  // Environment
  //
  // NOTE(JM): We use ArrayList instead of ArrayBuffer as
  // it is significantly faster.
  private[speedy] type Env = util.ArrayList[SValue]
  private[speedy] def emptyEnv: Env = new util.ArrayList[SValue](512)

  //
  // Kontinuation
  //
  // Whilst the machine is running, we ensure the kontStack is *never* empty.
  // We do this by pushing a KFinished continutaion on the initially empty stack, which
  // returns the final result (by raising it as a SpeedyHungry exception).

  private[this] def initialKontStack(): util.ArrayList[Kont] = {
    val kontStack = new util.ArrayList[Kont](128)
    kontStack.add(KFinished)
    kontStack
  }

  /** Kont, or continuation. Describes the next step for the machine
    * after an expression has been evaluated into a 'SValue'.
    */
  private[speedy] sealed trait Kont {

    /** Execute the continuation. */
    def execute(v: SValue): Unit
  }

  /** Final continuation; machine has computed final value */
  private[speedy] final case object KFinished extends Kont {
    def execute(v: SValue) = {
      throw SpeedyHungry(SResultFinalValue(v))
    }
  }

  private[speedy] final case class KOverApp(machine: Machine, newArgs: Array[SExprAtomic])
      extends Kont
      with SomeArrayEquals {

    private[this] val savedBase = machine.markBase()
    private[this] val frame = machine.frame
    private[this] val actuals = machine.actuals

    def execute(vfun: SValue) = {
      machine.restoreBase(savedBase);
      machine.restoreFrameAndActuals(frame, actuals)
      machine.enterApplication(vfun, newArgs)
    }
  }

  /** The function has been evaluated to a value. Now restore the environment and execute the application */
  private[speedy] final case class KArg(
      machine: Machine,
      newArgs: Array[SExpr],
  ) extends Kont
      with SomeArrayEquals {

    private[this] val savedBase = machine.markBase()
    private[this] val frame = machine.frame
    private[this] val actuals = machine.actuals

    def execute(vfun: SValue) = {
      machine.restoreBase(savedBase);
      machine.restoreFrameAndActuals(frame, actuals)
      machine.executeApplication(vfun, newArgs)
    }
  }

  /** The function-closure and arguments have been evaluated. Now execute the body. */
  private[speedy] final case class KFun(
      machine: Machine,
      closure: SValue.PClosure,
      actuals: util.ArrayList[SValue],
  ) extends Kont
      with SomeArrayEquals {

    private[this] val savedBase = machine.markBase()

    def execute(v: SValue) = {
      actuals.add(v)
      // Set frame/actuals to allow access to the function arguments and closure free-varables.
      machine.restoreBase(savedBase)
      machine.restoreFrameAndActuals(closure.frame, actuals)
      // Maybe push a continuation for the profiler
      val label = closure.label
      if (label != null) {
        machine.profile.addOpenEvent(label)
        machine.pushKont(KLeaveClosure(machine, label))
      }
      // Start evaluating the body of the closure.
      machine.popTempStackToBase()
      machine.ctrl = closure.expr
    }
  }

  /** The builtin arguments have been evaluated. Now execute the builtin. */
  private[speedy] final case class KBuiltin(
      machine: Machine,
      builtin: SBuiltin,
      actuals: util.ArrayList[SValue],
  ) extends Kont {

    private[this] val savedBase = machine.markBase()

    def execute(v: SValue) = {
      actuals.add(v)
      // A builtin has no free-vars, so we set the frame to null.
      machine.restoreBase(savedBase)
      machine.restoreFrameAndActuals(null, actuals)
      try {
        builtin.execute(actuals, machine)
      } catch {
        // We turn arithmetic exceptions into a daml exception that can be caught.
        case e: ArithmeticException =>
          throw DamlEArithmeticError(e.getMessage)
      }
    }
  }

  /** The function's partial-arguments have been evaluated. Construct and return the PAP */
  private[speedy] final case class KPap(
      machine: Machine,
      prim: SValue.Prim,
      actuals: util.ArrayList[SValue],
      arity: Int,
  ) extends Kont {

    def execute(v: SValue) = {
      actuals.add(v)
      machine.returnValue = SValue.SPAP(prim, actuals, arity)
    }
  }

  /** The scrutinee of a match has been evaluated, now match the alternatives against it. */
  private[speedy] def executeMatchAlts(machine: Machine, alts: Array[SCaseAlt], v: SValue): Unit = {
    val altOpt = v match {
      case SValue.SBool(b) =>
        alts.find { alt =>
          alt.pattern match {
            case SCPPrimCon(PCTrue) => b
            case SCPPrimCon(PCFalse) => !b
            case SCPDefault => true
            case _ => false
          }
        }
      case SValue.SVariant(_, _, rank1, arg) =>
        alts.find { alt =>
          alt.pattern match {
            case SCPVariant(_, _, rank2) if rank1 == rank2 =>
              machine.pushEnv(arg)
              true
            case SCPDefault => true
            case _ => false
          }
        }
      case SValue.SEnum(_, _, rank1) =>
        alts.find { alt =>
          alt.pattern match {
            case SCPEnum(_, _, rank2) => rank1 == rank2
            case SCPDefault => true
            case _ => false
          }
        }
      case SValue.SList(lst) =>
        alts.find { alt =>
          alt.pattern match {
            case SCPNil if lst.isEmpty => true
            case SCPCons if !lst.isEmpty =>
              val Some((head, tail)) = lst.pop
              machine.pushEnv(head)
              machine.pushEnv(SValue.SList(tail))
              true
            case SCPDefault => true
            case _ => false
          }
        }
      case SValue.SUnit =>
        alts.find { alt =>
          alt.pattern match {
            case SCPPrimCon(PCUnit) => true
            case SCPDefault => true
            case _ => false
          }
        }
      case SValue.SOptional(mbVal) =>
        alts.find { alt =>
          alt.pattern match {
            case SCPNone if mbVal.isEmpty => true
            case SCPSome =>
              mbVal match {
                case None => false
                case Some(x) =>
                  machine.pushEnv(x)
                  true
              }
            case SCPDefault => true
            case _ => false
          }
        }
      case SValue.SContractId(_) | SValue.SDate(_) | SValue.SNumeric(_) | SValue.SInt64(_) |
          SValue.SParty(_) | SValue.SText(_) | SValue.STimestamp(_) | SValue.SStruct(_, _) |
          SValue.SGenMap(_, _) | SValue.SRecord(_, _, _) | SValue.SAny(_, _) | SValue.STypeRep(_) |
          SValue.STNat(_) | _: SValue.SPAP | SValue.SToken | SValue.SBuiltinException(_, _) |
          SValue.SAnyException(_, _, _) =>
        crash("Match on non-matchable value")
    }

    machine.ctrl = altOpt
      .getOrElse(throw DamlEMatchError(s"No match for $v in ${alts.toList}"))
      .body
  }

  private[speedy] final case class KMatch(machine: Machine, alts: Array[SCaseAlt])
      extends Kont
      with SomeArrayEquals {

    private[this] val savedBase = machine.markBase()
    private[this] val frame = machine.frame
    private[this] val actuals = machine.actuals

    def execute(v: SValue) = {
      machine.restoreBase(savedBase);
      machine.restoreFrameAndActuals(frame, actuals)
      executeMatchAlts(machine, alts, v)
    }
  }

  /** Push the evaluated value to the array 'to', and start evaluating the expression 'next'.
    * This continuation is used to implement both function application and lets. In
    * the case of function application the arguments are pushed into the 'actuals' array of
    * the PAP that is being built, and in the case of lets the evaluated value is pushed
    * direy into the environment.
    */
  private[speedy] final case class KPushTo(
      machine: Machine,
      to: util.ArrayList[SValue],
      next: SExpr,
  ) extends Kont
      with SomeArrayEquals {

    private[this] val savedBase = machine.markBase()
    private[this] val frame = machine.frame
    private[this] val actuals = machine.actuals

    def execute(v: SValue) = {
      machine.restoreBase(savedBase);
      machine.restoreFrameAndActuals(frame, actuals)
      to.add(v)
      machine.ctrl = next
    }
  }

  private[speedy] final case class KFoldl(
      machine: Machine,
      func: SValue,
      var list: FrontStack[SValue],
  ) extends Kont
      with SomeArrayEquals {

    private[this] val frame = machine.frame
    private[this] val actuals = machine.actuals

    def execute(acc: SValue) = {
      list.pop match {
        case None =>
          machine.returnValue = acc
        case Some((item, rest)) =>
          machine.restoreFrameAndActuals(frame, actuals)
          // NOTE: We are "recycling" the current continuation with the
          // remainder of the list to avoid allocating a new continuation.
          list = rest
          machine.pushKont(this)
          machine.enterApplication(func, Array(SEValue(acc), SEValue(item)))
      }
    }
  }

  private[speedy] final case class KFoldr(
      machine: Machine,
      func: SValue,
      list: ImmArray[SValue],
      var lastIndex: Int,
  ) extends Kont
      with SomeArrayEquals {

    private[this] val frame = machine.frame
    private[this] val actuals = machine.actuals

    def execute(acc: SValue) = {
      if (lastIndex > 0) {
        machine.restoreFrameAndActuals(frame, actuals)
        val currentIndex = lastIndex - 1
        val item = list(currentIndex)
        lastIndex = currentIndex
        machine.pushKont(this) // NOTE: We've updated `lastIndex`.
        machine.enterApplication(func, Array(SEValue(item), SEValue(acc)))
      } else {
        machine.returnValue = acc
      }
    }
  }

  // NOTE: See the explanation above the definition of `SBFoldr` on why we need
  // this continuation and what it does.
  private[speedy] final case class KFoldr1Map(
      machine: Machine,
      func: SValue,
      var list: FrontStack[SValue],
      var revClosures: FrontStack[SValue],
      init: SValue,
  ) extends Kont
      with SomeArrayEquals {

    private[this] val frame = machine.frame
    private[this] val actuals = machine.actuals

    def execute(closure: SValue) = {
      revClosures = closure +: revClosures
      list.pop match {
        case None =>
          machine.pushKont(KFoldr1Reduce(machine, revClosures))
          machine.returnValue = init
        case Some((item, rest)) =>
          machine.restoreFrameAndActuals(frame, actuals)
          list = rest
          machine.pushKont(this) // NOTE: We've updated `revClosures` and `list`.
          machine.enterApplication(func, Array(SEValue(item)))
      }
    }
  }

  // NOTE: See the explanation above the definition of `SBFoldr` on why we need
  // this continuation and what it does.
  private[speedy] final case class KFoldr1Reduce(
      machine: Machine,
      var revClosures: FrontStack[SValue],
  ) extends Kont
      with SomeArrayEquals {

    private[this] val frame = machine.frame
    private[this] val actuals = machine.actuals

    def execute(acc: SValue) = {
      revClosures.pop match {
        case None =>
          machine.returnValue = acc
        case Some((closure, rest)) =>
          machine.restoreFrameAndActuals(frame, actuals)
          revClosures = rest
          machine.pushKont(this) // NOTE: We've updated `revClosures`.
          machine.enterApplication(closure, Array(SEValue(acc)))
      }
    }
  }

  /** Store the evaluated value in the definition and in the 'SEVal' from which the
    * expression came from. This in principle makes top-level values lazy. It is a
    * useful optimization to allow creation of large constants (for example records
    * that are repeatedly accessed. In older compilers which did not use the builtin
    * record and struct updates this solves the blow-up which would happen when a
    * large record is updated multiple times.
    */
  private[speedy] final case class KCacheVal(
      machine: Machine,
      v: SEVal,
      defn: SDefinition,
      stack_trace: List[Location],
  ) extends Kont {

    def execute(sv: SValue): Unit = {
      machine.pushStackTrace(stack_trace)
      v.setCached(sv, stack_trace)
      defn.setCached(sv, stack_trace)
      machine.returnValue = sv
    }
  }

  private[speedy] final case class KCacheContract(
      machine: Machine,
      templateId: Ref.TypeConName,
      cid: V.ContractId,
  ) extends Kont {

    def execute(sv: SValue): Unit = {
      val cached = SBuiltin.extractCachedContract(templateId, sv)
      machine.withOnLedger("KCacheContract") { onLedger =>
        onLedger.cachedContracts = onLedger.cachedContracts.updated(cid, cached)
        machine.returnValue = cached.value
      }
    }
  }

  /** KCloseExercise. Marks an open-exercise which needs to be closed. Either:
    * (1) by 'endExercises' if this continuation is entered normally, or
    * (2) by 'abortExercises' if we unwind the stack through this continuation
    */
  private[speedy] final case class KCloseExercise(machine: Machine) extends Kont {

    def execute(exerciseResult: SValue) = {
      machine.withOnLedger("KCloseExercise") { onLedger =>
        onLedger.ptx = onLedger.ptx.endExercises(exerciseResult.toValue)
        checkAborted(onLedger.ptx)
      }
      machine.returnValue = exerciseResult
    }
  }

  /** KTryCatchHandler marks the kont-stack to allow unwinding when throw is executed. If
    * the continuation is entered normally, the environment is restored but the handler is
    * not executed.  When a throw is executed, the kont-stack is unwound to the nearest
    * enclosing KTryCatchHandler (if there is one), and the code for the handler executed.
    */
  private[speedy] final case class KTryCatchHandler(
      machine: Machine,
      handler: SExpr,
  ) extends Kont
      with SomeArrayEquals {

    private[this] val savedBase = machine.markBase()
    private[this] val frame = machine.frame
    private[this] val actuals = machine.actuals

    // we must restore when catching a throw, or for normal execution
    def restore() = {
      machine.restoreBase(savedBase)
      machine.restoreFrameAndActuals(frame, actuals)
    }

    def execute(v: SValue) = {
      restore()
      machine.withOnLedger("KTryCatchHandler") { onLedger =>
        onLedger.ptx = onLedger.ptx.endTry
      }
      machine.returnValue = v
    }
  }

  private[speedy] def throwUnhandledException(payload: SValue) = {
    payload match {
      case SValue.SAnyException(ty, _, innerValue) =>
        throw DamlEUnhandledException(ty, innerValue.toValue)
      case v =>
        crash(s"throwUnhandledException, applied to non-AnyException: $v")
    }
  }

  /** unwindToHandler is called when an exception is thrown by the builtin SBThrow or
    * re-thrown by the builtin SBTryHandler. If a catch-handler is found, we initiate
    * execution of the handler code (which might decide to re-throw). Otherwise we call
    * throwUnhandledException to apply the message function to the exception payload,
    * producing a text message.
    */
  private[speedy] def unwindToHandler(machine: Machine, payload: SValue): Unit = {
    @tailrec def unwind(): Option[KTryCatchHandler] = {
      if (machine.kontDepth() == 0) {
        None
      } else {
        machine.popKont() match {
          case handler: KTryCatchHandler =>
            Some(handler)
          case _: KCloseExercise =>
            machine.withOnLedger("unwindToHandler/KCloseExercise") { onLedger =>
              onLedger.ptx = onLedger.ptx.abortExercises
              checkAborted(onLedger.ptx)
            }
            unwind()
          case _ =>
            unwind()
        }
      }
    }
    unwind() match {
      case Some(kh) =>
        kh.restore()
        machine.popTempStackToBase()
        machine.ctrl = kh.handler
        machine.pushEnv(payload) // payload on stack where handler expects it
      case None =>
        machine.kontStack.clear()
        machine.env.clear()
        machine.envBase = 0
        throwUnhandledException(payload)
    }
  }

  /** A catch frame marks the point to which an exception (of type 'SErrorDamlException')
    * is unwound. The 'envSize' specifies the size to which the environment must be pruned.
    * If an exception is raised and 'KCatchSubmitMustFail' is found from kont-stack, then 'True' is
    * returned. If 'KCatchSubmitMustFail' is entered normally, then 'False' is returned.
    */
  private[speedy] final case class KCatchSubmitMustFail(machine: Machine) extends Kont {

    private[Speedy] val envSize = machine.env.size // used by: tryHandleSubmitMustFail

    def execute(v: SValue) = {
      machine.returnValue = SValue.SValue.False
    }
  }

  /** A location frame stores a location annotation found in the AST. */
  final case class KLocation(machine: Machine, location: Location) extends Kont {
    def execute(v: SValue) = {
      machine.returnValue = v
    }
  }

  /** Continuation produced by [[SELabelClsoure]] expressions. This is only
    * used during profiling. Its purpose is to attach a label to closures such
    * that entering the closure can write an "open event" with that label.
    */
  private[speedy] final case class KLabelClosure(machine: Machine, label: Profile.Label)
      extends Kont {
    def execute(v: SValue) = {
      v match {
        case SValue.SPAP(SValue.PClosure(_, expr, closure), args, arity) =>
          machine.returnValue = SValue.SPAP(SValue.PClosure(label, expr, closure), args, arity)
        case _ =>
          machine.returnValue = v
      }
    }
  }

  /** Continuation marking the exit of a closure. This is only used during
    * profiling.
    */
  private[speedy] final case class KLeaveClosure(machine: Machine, label: Profile.Label)
      extends Kont {
    def execute(v: SValue) = {
      machine.profile.addCloseEvent(label)
      machine.returnValue = v
    }
  }

  /** Internal exception thrown when a continuation result needs to be returned.
    *    Or machine execution has reached a final value.
    */
  private[speedy] final case class SpeedyHungry(result: SResult)
      extends RuntimeException
      with NoStackTrace

  private[speedy] def deriveTransactionSeed(
      submissionSeed: crypto.Hash,
      participant: Ref.ParticipantId,
      submissionTime: Time.Timestamp,
  ): InitialSeeding =
    InitialSeeding.TransactionSeed(
      crypto.Hash.deriveTransactionSeed(submissionSeed, participant, submissionTime)
    )

}
