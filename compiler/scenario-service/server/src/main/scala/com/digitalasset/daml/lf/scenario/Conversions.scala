// Copyright (c) 2021 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.lf.scenario

import com.daml.lf.data.{ImmArray, Numeric, Ref}
import com.daml.lf.ledger.EventId
import com.daml.lf.scenario.api.{v1 => proto}
import com.daml.lf.speedy.{SError, SValue, PartialTransaction => SPartialTransaction, TraceLog}
import com.daml.lf.transaction.{GlobalKey, Node => N, NodeId}
import com.daml.lf.ledger._
import com.daml.lf.value.{Value => V}

import scala.jdk.CollectionConverters._

final class Conversions(
    homePackageId: Ref.PackageId,
    ledger: ScenarioLedger,
    ptx: SPartialTransaction,
    traceLog: TraceLog,
    commitLocation: Option[Ref.Location],
    stackTrace: ImmArray[Ref.Location],
) {

  private val empty: proto.Empty = proto.Empty.newBuilder.build

  private val packageIdSelf: proto.PackageIdentifier =
    proto.PackageIdentifier.newBuilder.setSelf(empty).build

  // The ledger data will not contain information from the partial transaction at this point.
  // We need the mapping for converting error message so we manually add it here.
  private val ptxCoidToNodeId = ptx.nodes
    .collect { case (nodeId, node: N.NodeCreate[V.ContractId]) =>
      node.coid -> ledger.ptxEventId(nodeId)
    }

  private val coidToEventId = ledger.ledgerData.coidToNodeId ++ ptxCoidToNodeId

  private val nodes =
    ledger.ledgerData.nodeInfos.map(Function.tupled(convertNode))

  private val steps = ledger.scenarioSteps.map { case (idx, step) =>
    convertScenarioStep(idx.toInt, step)
  }

  def convertScenarioResult(svalue: SValue): proto.ScenarioResult = {
    val builder = proto.ScenarioResult.newBuilder
      .addAllNodes(nodes.asJava)
      .addAllScenarioSteps(steps.asJava)
      .setReturnValue(convertSValue(svalue))
      .setFinalTime(ledger.currentTime.micros)
    traceLog.iterator.foreach { entry =>
      builder.addTraceLog(convertSTraceMessage(entry))
    }
    builder.build
  }

  def convertScenarioError(
      err: SError.SError
  ): proto.ScenarioError = {
    val builder = proto.ScenarioError.newBuilder
      .addAllNodes(nodes.asJava)
      .addAllScenarioSteps(steps.asJava)
      .setLedgerTime(ledger.currentTime.micros)

    traceLog.iterator.foreach { entry =>
      builder.addTraceLog(convertSTraceMessage(entry))
    }

    def setCrash(reason: String) = builder.setCrash(reason)

    commitLocation.foreach { loc =>
      builder.setCommitLoc(convertLocation(loc))
    }

    builder.addAllStackTrace(stackTrace.map(convertLocation).toSeq.asJava)

    builder.setPartialTransaction(
      convertPartialTransaction(ptx)
    )

    err match {
      case SError.SErrorCrash(reason) => setCrash(reason)
      case SError.SRequiresOnLedger(operation) => setCrash(operation)

      case SError.DamlEMatchError(reason) =>
        setCrash(reason)
      case SError.DamlEArithmeticError(reason) =>
        setCrash(reason)
      case SError.DamlEUnhandledException(_, exc) =>
        // TODO https://github.com/digital-asset/daml/issues/8020
        // Add an error case for unhandled exceptions to the scenario proto.
        setCrash(exc.toString)
      case SError.DamlEUserError(msg) =>
        builder.setUserError(msg)

      case SError.DamlETransactionError(reason) =>
        setCrash(reason)

      case SError.DamlETemplatePreconditionViolated(tid, optLoc, arg) =>
        val uepvBuilder = proto.ScenarioError.TemplatePreconditionViolated.newBuilder
        optLoc.map(convertLocation).foreach(uepvBuilder.setLocation)
        builder.setTemplatePrecondViolated(
          uepvBuilder
            .setTemplateId(convertIdentifier(tid))
            .setArg(convertValue(arg))
            .build
        )
      case SError.DamlELocalContractNotActive(coid, tid, consumedBy) =>
        builder.setUpdateLocalContractNotActive(
          proto.ScenarioError.ContractNotActive.newBuilder
            .setContractRef(mkContractRef(coid, tid))
            .setConsumedBy(proto.NodeId.newBuilder.setId(consumedBy.toString).build)
            .build
        )
      case SError.DamlEFailedAuthorization(nid, fa) =>
        builder.setScenarioCommitError(
          proto.CommitError.newBuilder
            .setFailedAuthorizations(convertFailedAuthorization(nid, fa))
            .build
        )

      case SError.DamlECreateEmptyContractKeyMaintainers(tid, arg, key) =>
        builder.setCreateEmptyContractKeyMaintainers(
          proto.ScenarioError.CreateEmptyContractKeyMaintainers.newBuilder
            .setArg(convertValue(arg))
            .setTemplateId(convertIdentifier(tid))
            .setKey(convertValue(key))
        )

      case SError.DamlEFetchEmptyContractKeyMaintainers(tid, key) =>
        builder.setFetchEmptyContractKeyMaintainers(
          proto.ScenarioError.FetchEmptyContractKeyMaintainers.newBuilder
            .setTemplateId(convertIdentifier(tid))
            .setKey(convertValue(key))
        )

      case SError.ScenarioErrorContractNotEffective(coid, tid, effectiveAt) =>
        builder.setScenarioContractNotEffective(
          proto.ScenarioError.ContractNotEffective.newBuilder
            .setEffectiveAt(effectiveAt.micros)
            .setContractRef(mkContractRef(coid, tid))
            .build
        )

      case SError.ScenarioErrorContractNotActive(coid, tid, consumedBy) =>
        builder.setScenarioContractNotActive(
          proto.ScenarioError.ContractNotActive.newBuilder
            .setContractRef(mkContractRef(coid, tid))
            .setConsumedBy(convertEventId(consumedBy))
            .build
        )

      case SError.ScenarioErrorContractNotVisible(coid, tid, actAs, readAs, observers) =>
        builder.setScenarioContractNotVisible(
          proto.ScenarioError.ContractNotVisible.newBuilder
            .setContractRef(mkContractRef(coid, tid))
            .addAllActAs(actAs.map(convertParty(_)).asJava)
            .addAllReadAs(readAs.map(convertParty(_)).asJava)
            .addAllObservers(observers.map(convertParty).asJava)
            .build
        )

      case SError.ScenarioErrorContractKeyNotVisible(coid, gk, actAs, readAs, stakeholders) =>
        builder.setScenarioContractKeyNotVisible(
          proto.ScenarioError.ContractKeyNotVisible.newBuilder
            .setContractRef(mkContractRef(coid, gk.templateId))
            .setKey(convertValue(gk.key))
            .addAllActAs(actAs.map(convertParty(_)).asJava)
            .addAllReadAs(readAs.map(convertParty(_)).asJava)
            .addAllStakeholders(stakeholders.map(convertParty).asJava)
            .build
        )

      case SError.ScenarioErrorCommitError(commitError) =>
        builder.setScenarioCommitError(
          convertCommitError(commitError)
        )
      case SError.ScenarioErrorMustFailSucceeded(tx @ _) =>
        builder.setScenarioMustfailSucceeded(empty)

      case SError.ScenarioErrorInvalidPartyName(party, _) =>
        builder.setScenarioInvalidPartyName(party)

      case SError.ScenarioErrorPartyAlreadyExists(party) =>
        builder.setScenarioPartyAlreadyExists(party)

      case wtc: SError.DamlEWronglyTypedContract =>
        sys.error(
          s"Got unexpected DamlEWronglyTypedContract error in scenario service: $wtc. Note that in the scenario service this error should never surface since contract fetches are all type checked."
        )
    }
    builder.build
  }

  def convertCommitError(commitError: ScenarioLedger.CommitError): proto.CommitError = {
    val builder = proto.CommitError.newBuilder
    commitError match {
      case ScenarioLedger.CommitError.UniqueKeyViolation(gk) =>
        builder.setUniqueKeyViolation(convertGlobalKey(gk.gk))
    }
    builder.build
  }

  def convertGlobalKey(globalKey: GlobalKey): proto.GlobalKey = {
    proto.GlobalKey.newBuilder
      .setTemplateId(convertIdentifier(globalKey.templateId))
      .setKey(convertValue(globalKey.key))
      .build
  }

  def convertSValue(svalue: SValue): proto.Value = {
    def unserializable(what: String): proto.Value =
      proto.Value.newBuilder.setUnserializable(what).build
    try {
      convertValue(svalue.toValue)
    } catch {
      case _: SError.SErrorCrash => {
        // We cannot rely on serializability information since we do not have that available in the IDE.
        // We also cannot simply pattern match on SValue since the unserializable values can be nested, e.g.,
        // a function ina record.
        // We could recurse on SValue to produce slightly better error messages if we
        // encounter an unserializable type but that doesn’t seem worth the effort, especially
        // given that the error would still be on speedy expressions.
        unserializable("Unserializable scenario result")
      }
    }
  }

  def convertSTraceMessage(msgAndLoc: (String, Option[Ref.Location])): proto.TraceMessage = {
    val builder = proto.TraceMessage.newBuilder
    msgAndLoc._2.map(loc => builder.setLocation(convertLocation(loc)))
    builder.setMessage(msgAndLoc._1).build
  }

  def convertFailedAuthorization(
      nodeId: NodeId,
      fa: FailedAuthorization,
  ): proto.FailedAuthorizations = {
    val builder = proto.FailedAuthorizations.newBuilder
    builder.addFailedAuthorizations {
      val faBuilder = proto.FailedAuthorization.newBuilder
      faBuilder.setNodeId(convertTxNodeId(nodeId))
      fa match {
        case FailedAuthorization.CreateMissingAuthorization(
              templateId,
              optLocation,
              authParties,
              reqParties,
            ) =>
          val cmaBuilder =
            proto.FailedAuthorization.CreateMissingAuthorization.newBuilder
              .setTemplateId(convertIdentifier(templateId))
              .addAllAuthorizingParties(authParties.map(convertParty).asJava)
              .addAllRequiredAuthorizers(reqParties.map(convertParty).asJava)
          optLocation.map(loc => cmaBuilder.setLocation(convertLocation(loc)))
          faBuilder.setCreateMissingAuthorization(cmaBuilder.build)

        case FailedAuthorization.MaintainersNotSubsetOfSignatories(
              templateId,
              optLocation,
              signatories,
              maintainers,
            ) =>
          val maintNotSignBuilder =
            proto.FailedAuthorization.MaintainersNotSubsetOfSignatories.newBuilder
              .setTemplateId(convertIdentifier(templateId))
              .addAllSignatories(signatories.map(convertParty).asJava)
              .addAllMaintainers(maintainers.map(convertParty).asJava)
          optLocation.map(loc => maintNotSignBuilder.setLocation(convertLocation(loc)))
          faBuilder.setMaintainersNotSubsetOfSignatories(maintNotSignBuilder.build)

        case fma: FailedAuthorization.FetchMissingAuthorization =>
          val fmaBuilder =
            proto.FailedAuthorization.FetchMissingAuthorization.newBuilder
              .setTemplateId(convertIdentifier(fma.templateId))
              .addAllAuthorizingParties(fma.authorizingParties.map(convertParty).asJava)
              .addAllStakeholders(fma.stakeholders.map(convertParty).asJava)
          fma.optLocation.map(loc => fmaBuilder.setLocation(convertLocation(loc)))
          faBuilder.setFetchMissingAuthorization(fmaBuilder.build)

        case FailedAuthorization.ExerciseMissingAuthorization(
              templateId,
              choiceId,
              optLocation,
              authParties,
              reqParties,
            ) =>
          val emaBuilder =
            proto.FailedAuthorization.ExerciseMissingAuthorization.newBuilder
              .setTemplateId(convertIdentifier(templateId))
              .setChoiceId(choiceId)
              .addAllAuthorizingParties(authParties.map(convertParty).asJava)
              .addAllRequiredAuthorizers(reqParties.map(convertParty).asJava)
          optLocation.map(loc => emaBuilder.setLocation(convertLocation(loc)))
          faBuilder.setExerciseMissingAuthorization(emaBuilder.build)
        case FailedAuthorization.NoSignatories(templateId, optLocation) =>
          val nsBuilder =
            proto.FailedAuthorization.NoSignatories.newBuilder
              .setTemplateId(convertIdentifier(templateId))
          optLocation.map(loc => nsBuilder.setLocation(convertLocation(loc)))
          faBuilder.setNoSignatories(nsBuilder.build)

        case FailedAuthorization.NoControllers(templateId, choiceId, optLocation) =>
          val ncBuilder =
            proto.FailedAuthorization.NoControllers.newBuilder
              .setTemplateId(convertIdentifier(templateId))
              .setChoiceId(choiceId)
          optLocation.map(loc => ncBuilder.setLocation(convertLocation(loc)))
          faBuilder.setNoControllers(ncBuilder.build)

        case FailedAuthorization.LookupByKeyMissingAuthorization(
              templateId,
              optLocation,
              maintainers,
              authorizers,
            ) =>
          val lbkmaBuilder =
            proto.FailedAuthorization.LookupByKeyMissingAuthorization.newBuilder
              .setTemplateId(convertIdentifier(templateId))
              .addAllMaintainers(maintainers.map(convertParty).asJava)
              .addAllAuthorizingParties(authorizers.map(convertParty).asJava)
          optLocation.foreach(loc => lbkmaBuilder.setLocation(convertLocation(loc)))
          faBuilder.setLookupByKeyMissingAuthorization(lbkmaBuilder)
      }
      faBuilder.build
    }

    builder.build
  }

  def mkContractRef(coid: V.ContractId, templateId: Ref.Identifier): proto.ContractRef =
    proto.ContractRef.newBuilder
      .setRelative(false)
      .setContractId(coidToEventId(coid).toLedgerString)
      .setTemplateId(convertIdentifier(templateId))
      .build

  def convertScenarioStep(
      stepId: Int,
      step: ScenarioLedger.ScenarioStep,
  ): proto.ScenarioStep = {
    val builder = proto.ScenarioStep.newBuilder
    builder.setStepId(stepId)
    step match {
      case ScenarioLedger.Commit(txId, rtx, optLocation) =>
        val commitBuilder = proto.ScenarioStep.Commit.newBuilder
        optLocation.map { loc =>
          commitBuilder.setLocation(convertLocation(loc))
        }
        builder.setCommit(
          commitBuilder
            .setTxId(txId.index)
            .setTx(convertTransaction(rtx))
            .build
        )
      case ScenarioLedger.PassTime(dt) =>
        builder.setPassTime(dt)
      case ScenarioLedger.AssertMustFail(actAs, readAs, optLocation, time, txId) =>
        val assertBuilder = proto.ScenarioStep.AssertMustFail.newBuilder
        optLocation.map { loc =>
          assertBuilder.setLocation(convertLocation(loc))
        }
        builder
          .setAssertMustFail(
            assertBuilder
              .addAllActAs(actAs.map(convertParty(_)).asJava)
              .addAllReadAs(readAs.map(convertParty(_)).asJava)
              .setTime(time.micros)
              .setTxId(txId.index)
              .build
          )
    }
    builder.build
  }

  def convertTransaction(
      rtx: ScenarioLedger.RichTransaction
  ): proto.Transaction = {
    proto.Transaction.newBuilder
      .addAllActAs(rtx.actAs.map(convertParty(_)).asJava)
      .addAllReadAs(rtx.readAs.map(convertParty(_)).asJava)
      .setEffectiveAt(rtx.effectiveAt.micros)
      .addAllRoots(rtx.transaction.roots.map(convertNodeId(rtx.transactionId, _)).toSeq.asJava)
      .addAllNodes(rtx.transaction.nodes.keys.map(convertNodeId(rtx.transactionId, _)).asJava)
      .setFailedAuthorizations(
        proto.FailedAuthorizations.newBuilder.build
      )
      .build
  }

  def convertPartialTransaction(ptx: SPartialTransaction): proto.PartialTransaction = {
    val builder = proto.PartialTransaction.newBuilder
      .addAllNodes(ptx.nodes.map(convertNode).asJava)
      .addAllRoots(
        ptx.context.children.toImmArray.toSeq.sortBy(_.index).map(convertTxNodeId).asJava
      )

    ptx.context.info match {
      case ctx: SPartialTransaction.ExercisesContextInfo =>
        val ecBuilder = proto.ExerciseContext.newBuilder
          .setTargetId(mkContractRef(ctx.targetId, ctx.templateId))
          .setChoiceId(ctx.choiceId)
          .setChosenValue(convertValue(ctx.chosenValue))
        ctx.optLocation.map(loc => ecBuilder.setExerciseLocation(convertLocation(loc)))
        builder.setExerciseContext(ecBuilder.build)
      case _: SPartialTransaction.TryContextInfo =>
        // TODO: https://github.com/digital-asset/daml/issues/8020
        //  handle catch context
        sys.error("exception not supported")
      case _: SPartialTransaction.RootContextInfo =>
    }
    builder.build
  }

  def convertEventId(nodeId: EventId): proto.NodeId =
    proto.NodeId.newBuilder.setId(nodeId.toLedgerString).build

  def convertNodeId(trId: Ref.LedgerString, nodeId: NodeId): proto.NodeId =
    proto.NodeId.newBuilder.setId(EventId(trId, nodeId).toLedgerString).build

  def convertTxNodeId(nodeId: NodeId): proto.NodeId =
    proto.NodeId.newBuilder.setId(nodeId.index.toString).build

  def convertNode(eventId: EventId, nodeInfo: ScenarioLedger.LedgerNodeInfo): proto.Node = {
    val builder = proto.Node.newBuilder
    builder
      .setNodeId(convertEventId(eventId))
      .setEffectiveAt(nodeInfo.effectiveAt.micros)
      .addAllReferencedBy(nodeInfo.referencedBy.map(convertEventId).asJava)
      .addAllDisclosures(nodeInfo.disclosures.toList.map {
        case (party, ScenarioLedger.Disclosure(txId, explicit)) =>
          proto.Disclosure.newBuilder
            .setParty(convertParty(party))
            .setSinceTxId(txId.index)
            .setExplicit(explicit)
            .build
      }.asJava)

    nodeInfo.consumedBy
      .map(eventId => builder.setConsumedBy(convertEventId(eventId)))
    nodeInfo.parent
      .map(eventId => builder.setParent(convertEventId(eventId)))

    nodeInfo.node match {
      case _: N.NodeRollback[_] =>
        // TODO https://github.com/digital-asset/daml/issues/8020
        sys.error("rollback nodes are not supported")
      case create: N.NodeCreate[V.ContractId] =>
        val createBuilder =
          proto.Node.Create.newBuilder
            .setContractInstance(
              proto.ContractInstance.newBuilder
                .setTemplateId(convertIdentifier(create.coinst.template))
                .setValue(convertValue(create.coinst.arg))
                .build
            )
            .addAllSignatories(create.signatories.map(convertParty).asJava)
            .addAllStakeholders(create.stakeholders.map(convertParty).asJava)

        create.optLocation.map(loc => builder.setLocation(convertLocation(loc)))
        builder.setCreate(createBuilder.build)
      case fetch: N.NodeFetch[V.ContractId] =>
        builder.setFetch(
          proto.Node.Fetch.newBuilder
            .setContractId(coidToEventId(fetch.coid).toLedgerString)
            .setTemplateId(convertIdentifier(fetch.templateId))
            .addAllSignatories(fetch.signatories.map(convertParty).asJava)
            .addAllStakeholders(fetch.stakeholders.map(convertParty).asJava)
            .build
        )
      case ex: N.NodeExercises[NodeId, V.ContractId] =>
        ex.optLocation.map(loc => builder.setLocation(convertLocation(loc)))
        builder.setExercise(
          proto.Node.Exercise.newBuilder
            .setTargetContractId(coidToEventId(ex.targetCoid).toLedgerString)
            .setTemplateId(convertIdentifier(ex.templateId))
            .setChoiceId(ex.choiceId)
            .setConsuming(ex.consuming)
            .addAllActingParties(ex.actingParties.map(convertParty).asJava)
            .setChosenValue(convertValue(ex.chosenValue))
            .addAllSignatories(ex.signatories.map(convertParty).asJava)
            .addAllStakeholders(ex.stakeholders.map(convertParty).asJava)
            .addAllChildren(
              ex.children
                .map(convertNodeId(eventId.transactionId, _))
                .toSeq
                .asJava
            )
            .build
        )

      case lbk: N.NodeLookupByKey[V.ContractId] =>
        lbk.optLocation.foreach(loc => builder.setLocation(convertLocation(loc)))
        val lbkBuilder = proto.Node.LookupByKey.newBuilder
          .setTemplateId(convertIdentifier(lbk.templateId))
          .setKeyWithMaintainers(convertKeyWithMaintainers(lbk.versionedKey))
        lbk.result.foreach(cid => lbkBuilder.setContractId(coidToEventId(cid).toLedgerString))
        builder.setLookupByKey(lbkBuilder)

    }
    builder.build
  }

  def convertKeyWithMaintainers(
      key: N.KeyWithMaintainers[V.VersionedValue[V.ContractId]]
  ): proto.KeyWithMaintainers = {
    proto.KeyWithMaintainers
      .newBuilder()
      .setKey(convertVersionedValue(key.key))
      .addAllMaintainers(key.maintainers.map(convertParty).asJava)
      .build()
  }

  def convertNode(
      nodeWithId: (NodeId, N.GenNode[NodeId, V.ContractId])
  ): proto.Node = {
    val (nodeId, node) = nodeWithId
    val builder = proto.Node.newBuilder
    builder
      .setNodeId(proto.NodeId.newBuilder.setId(nodeId.index.toString).build)
    // FIXME(JM): consumedBy, parent, ...
    node match {
      case _: N.NodeRollback[_] =>
        // TODO https://github.com/digital-asset/daml/issues/8020
        sys.error("rollback nodes are not supported")
      case create: N.NodeCreate[V.ContractId] =>
        val createBuilder =
          proto.Node.Create.newBuilder
            .setContractInstance(
              proto.ContractInstance.newBuilder
                .setTemplateId(convertIdentifier(create.coinst.template))
                .setValue(convertValue(create.coinst.arg))
                .build
            )
            .addAllSignatories(create.signatories.map(convertParty).asJava)
            .addAllStakeholders(create.stakeholders.map(convertParty).asJava)
        create.versionedKey.foreach(key =>
          createBuilder.setKeyWithMaintainers(convertKeyWithMaintainers(key))
        )
        create.optLocation.map(loc => builder.setLocation(convertLocation(loc)))
        builder.setCreate(createBuilder.build)
      case fetch: N.NodeFetch[V.ContractId] =>
        builder.setFetch(
          proto.Node.Fetch.newBuilder
            .setContractId(coidToEventId(fetch.coid).toLedgerString)
            .setTemplateId(convertIdentifier(fetch.templateId))
            .addAllSignatories(fetch.signatories.map(convertParty).asJava)
            .addAllStakeholders(fetch.stakeholders.map(convertParty).asJava)
            .build
        )
      case ex: N.NodeExercises[NodeId, V.ContractId] =>
        ex.optLocation.map(loc => builder.setLocation(convertLocation(loc)))
        builder.setExercise(
          proto.Node.Exercise.newBuilder
            .setTargetContractId(coidToEventId(ex.targetCoid).toLedgerString)
            .setTemplateId(convertIdentifier(ex.templateId))
            .setChoiceId(ex.choiceId)
            .setConsuming(ex.consuming)
            .addAllActingParties(ex.actingParties.map(convertParty).asJava)
            .setChosenValue(convertValue(ex.chosenValue))
            .addAllSignatories(ex.signatories.map(convertParty).asJava)
            .addAllStakeholders(ex.stakeholders.map(convertParty).asJava)
            .addAllChildren(
              ex.children
                .map(nid => proto.NodeId.newBuilder.setId(nid.index.toString).build)
                .toSeq
                .asJava
            )
            .build
        )

      case lookup: N.NodeLookupByKey[V.ContractId] =>
        lookup.optLocation.map(loc => builder.setLocation(convertLocation(loc)))
        builder.setLookupByKey({
          val builder = proto.Node.LookupByKey.newBuilder
            .setKeyWithMaintainers(convertKeyWithMaintainers(lookup.versionedKey))
          lookup.result.foreach(cid => builder.setContractId(coidToEventId(cid).toLedgerString))
          builder.build
        })
    }
    builder.build
  }

  def convertPackageId(pkg: Ref.PackageId): proto.PackageIdentifier =
    if (pkg == homePackageId)
      // Reconstitute the self package reference.
      packageIdSelf
    else
      proto.PackageIdentifier.newBuilder.setPackageId(pkg).build

  def convertIdentifier(identifier: Ref.Identifier): proto.Identifier =
    proto.Identifier.newBuilder
      .setPackage(convertPackageId(identifier.packageId))
      .setName(identifier.qualifiedName.toString)
      .build

  def convertLocation(loc: Ref.Location): proto.Location = {
    val (sline, scol) = loc.start
    val (eline, ecol) = loc.end
    proto.Location.newBuilder
      .setPackage(convertPackageId(loc.packageId))
      .setModule(loc.module.toString)
      .setDefinition(loc.definition)
      .setStartLine(sline)
      .setStartCol(scol)
      .setEndLine(eline)
      .setEndCol(ecol)
      .build

  }

  private def convertVersionedValue(value: V.VersionedValue[V.ContractId]): proto.Value =
    convertValue(value.value)

  def convertValue(value: V[V.ContractId]): proto.Value = {
    val builder = proto.Value.newBuilder
    value match {
      case V.ValueRecord(tycon, fields) =>
        val rbuilder = proto.Record.newBuilder
        tycon.map(x => rbuilder.setRecordId(convertIdentifier(x)))
        builder.setRecord(
          rbuilder
            .addAllFields(
              fields.toSeq.map { case (optName, fieldValue) =>
                val builder = proto.Field.newBuilder
                optName.foreach(builder.setLabel)
                builder
                  .setValue(convertValue(fieldValue))
                  .build
              }.asJava
            )
            .build
        )
      case V.ValueBuiltinException(tag, value) =>
        val vbuilder = proto.BuiltinException.newBuilder
        builder.setBuiltinException(
          vbuilder
            .setTag(tag)
            .setValue(convertValue(value))
            .build
        )
      case V.ValueVariant(tycon, variant, value) =>
        val vbuilder = proto.Variant.newBuilder
        tycon.foreach(x => vbuilder.setVariantId(convertIdentifier(x)))
        builder.setVariant(
          vbuilder
            .setConstructor(variant)
            .setValue(convertValue(value))
            .build
        )
      case V.ValueEnum(tycon, constructor) =>
        val eBuilder = proto.Enum.newBuilder.setConstructor(constructor)
        tycon.foreach(x => eBuilder.setEnumId(convertIdentifier(x)))
        builder.setEnum(eBuilder.build)
      case V.ValueContractId(coid) =>
        builder.setContractId(coidToEventId(coid).toLedgerString)
      case V.ValueList(values) =>
        builder.setList(
          proto.List.newBuilder
            .addAllElements(
              values
                .map(convertValue)
                .toImmArray
                .toSeq
                .asJava
            )
            .build
        )
      case V.ValueInt64(v) => builder.setInt64(v)
      case V.ValueNumeric(d) => builder.setDecimal(Numeric.toString(d))
      case V.ValueText(t) => builder.setText(t)
      case V.ValueTimestamp(ts) => builder.setTimestamp(ts.micros)
      case V.ValueDate(d) => builder.setDate(d.days)
      case V.ValueParty(p) => builder.setParty(p)
      case V.ValueBool(b) => builder.setBool(b)
      case V.ValueUnit => builder.setUnit(empty)
      case V.ValueOptional(mbV) =>
        val optionalBuilder = proto.Optional.newBuilder
        mbV match {
          case None => ()
          case Some(v) => optionalBuilder.setValue(convertValue(v))
        }
        builder.setOptional(optionalBuilder)
      case V.ValueTextMap(map) =>
        val mapBuilder = proto.Map.newBuilder
        map.toImmArray.foreach { case (k, v) =>
          mapBuilder.addEntries(proto.Map.Entry.newBuilder().setKey(k).setValue(convertValue(v)))
          ()
        }
        builder.setMap(mapBuilder)
      case V.ValueGenMap(entries) =>
        val mapBuilder = proto.GenMap.newBuilder
        entries.foreach { case (k, v) =>
          mapBuilder.addEntries(
            proto.GenMap.Entry.newBuilder().setKey(convertValue(k)).setValue(convertValue(v))
          )
          ()
        }
        builder.setGenMap(mapBuilder)
    }
    builder.build
  }

  def convertParty(p: Ref.Party): proto.Party =
    proto.Party.newBuilder.setParty(p).build

}
