// Copyright (c) 2021 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.participant.state.kvutils.tools.integritycheck

import java.time.{Duration, Instant}

import com.daml.ledger.participant.state.kvutils.tools.integritycheck.ReadServiceStateUpdateComparison.{
  DefaultNormalizationSettings,
  NormalizationSettings,
}
import com.daml.ledger.participant.state.v1.Update.{
  CommandRejected,
  ConfigurationChangeRejected,
  TransactionAccepted,
}
import com.daml.ledger.participant.state.v1._
import com.daml.lf.crypto
import com.daml.lf.data.Relation.Relation
import com.daml.lf.data.{Ref, Time}
import com.daml.lf.transaction.test.TransactionBuilder
import com.daml.lf.transaction.{BlindingInfo, CommittedTransaction, NodeId}
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.wordspec.AsyncWordSpec

final class StateUpdateComparisonSpec
    extends AsyncWordSpec
    with TableDrivenPropertyChecks
    with Matchers {
  "compareUpdates" should {
    "ignore rejection reason for ConfigurationChangeRejected updates" in {
      val left = aConfigurationChangeRejected.copy(rejectionReason = "one reason")
      val right = aConfigurationChangeRejected.copy(rejectionReason = "another reason")

      ReadServiceStateUpdateComparison
        .compareUpdates(left, right, DefaultNormalizationSettings)
        .map(_ => succeed)
    }

    "ignore rejection reason for CommandRejected updates" in {
      val rejectionReasons = Table(
        ("Left", "Right"),
        RejectionReason.Disputed("a") -> RejectionReason.Disputed("b"),
        RejectionReason.Inconsistent("a") -> RejectionReason.Inconsistent("b"),
        RejectionReason.InvalidLedgerTime("a") -> RejectionReason.InvalidLedgerTime("b"),
        RejectionReason.PartyNotKnownOnLedger("a") -> RejectionReason.PartyNotKnownOnLedger("b"),
        RejectionReason.ResourcesExhausted("a") -> RejectionReason.ResourcesExhausted("b"),
        RejectionReason.SubmitterCannotActViaParticipant("a") -> RejectionReason
          .SubmitterCannotActViaParticipant("b"),
      )
      forAll(rejectionReasons) { case (left, right) =>
        ReadServiceStateUpdateComparison
          .compareUpdates(
            aCommandRejectedUpdate.copy(reason = left),
            aCommandRejectedUpdate.copy(reason = right),
            DefaultNormalizationSettings,
          )
          .map(_ => succeed)
      }
    }

    "ignore blinding info for TransactionAccepted updates" in {
      val left = aTransactionAcceptedUpdate.copy(blindingInfo = None)
      val blindingInfo = BlindingInfo(
        disclosure = Relation.from(Seq(NodeId(0) -> Set(Ref.Party.assertFromString("a party")))),
        divulgence = Map.empty,
      )
      val right =
        aTransactionAcceptedUpdate.copy(blindingInfo = Some(blindingInfo))

      ReadServiceStateUpdateComparison
        .compareUpdates(left, right, NormalizationSettings(ignoreBlindingInfo = true))
        .map(_ => succeed)
    }

    "ignore transaction ID for TransactionAccepted updates" in {
      val left = aTransactionAcceptedUpdate.copy(transactionId =
        TransactionId.assertFromString("a transaction ID")
      )
      val right = aTransactionAcceptedUpdate.copy(transactionId =
        TransactionId.assertFromString("another transaction ID")
      )

      ReadServiceStateUpdateComparison
        .compareUpdates(left, right, NormalizationSettings(ignoreTransactionId = true))
        .map(_ => succeed)
    }

    "ignore fetch and lookup by key nodes for TransactionAccepted updates" in {
      val left = aTransactionAcceptedUpdate.copy(transaction =
        buildATransaction(withFetchAndLookupByKeyNodes = true)
      )
      val right = aTransactionAcceptedUpdate.copy(transaction =
        buildATransaction(withFetchAndLookupByKeyNodes = false)
      )

      ReadServiceStateUpdateComparison
        .compareUpdates(left, right, NormalizationSettings(ignoreFetchAndLookupByKeyNodes = true))
        .map(_ => succeed)
    }
  }

  private lazy val aRecordTime = Time.Timestamp.now()
  private lazy val aConfigurationChangeRejected = ConfigurationChangeRejected(
    recordTime = Time.Timestamp.now(),
    submissionId = SubmissionId.assertFromString("a submission ID"),
    participantId = ParticipantId.assertFromString("a participant ID"),
    proposedConfiguration = Configuration(1L, TimeModel.reasonableDefault, Duration.ofMinutes(1)),
    rejectionReason = "a rejection reason",
  )
  private lazy val aCommandRejectedUpdate = CommandRejected(
    recordTime = Time.Timestamp.now(),
    submitterInfo = SubmitterInfo(
      actAs = List.empty,
      applicationId = ApplicationId.assertFromString("an application ID"),
      commandId = CommandId.assertFromString("a command ID"),
      deduplicateUntil = Instant.now(),
    ),
    reason = RejectionReason.Disputed("a rejection reason"),
  )
  private lazy val aTransactionAcceptedUpdate =
    TransactionAccepted(
      optSubmitterInfo = None,
      transactionMeta = TransactionMeta(
        ledgerEffectiveTime = aRecordTime,
        workflowId = None,
        submissionTime = Time.Timestamp.now(),
        submissionSeed = crypto.Hash.hashPrivateKey("dummy"),
        optUsedPackages = None,
        optNodeSeeds = None,
        optByKeyNodes = None,
      ),
      transaction = TransactionBuilder.EmptyCommitted,
      transactionId = TransactionId.assertFromString("anID"),
      recordTime = aRecordTime,
      divulgedContracts = List.empty,
      blindingInfo = None,
    )
  private lazy val aKeyMaintainer = "maintainer"
  private lazy val aDummyValue = TransactionBuilder.record("field" -> "value")

  def buildATransaction(withFetchAndLookupByKeyNodes: Boolean): CommittedTransaction = {
    val builder = new TransactionBuilder()
    val create1 = create("#someContractId")
    val create2 = create("#otherContractId")
    val fetch1 = builder.fetch(create1)
    val lookup1 = builder.lookupByKey(create1, found = true)
    val fetch2 = builder.fetch(create2)
    val lookup2 = builder.lookupByKey(create2, found = true)
    val exercise = builder.exercise(
      contract = create1,
      choice = "DummyChoice",
      consuming = false,
      actingParties = Set(aKeyMaintainer),
      argument = aDummyValue,
      byKey = false,
    )
    val exerciseNodeId = builder.add(exercise)
    if (withFetchAndLookupByKeyNodes) {
      builder.add(fetch1)
      builder.add(lookup1)
      builder.add(fetch2, exerciseNodeId)
      builder.add(lookup2, exerciseNodeId)
    }
    builder.buildCommitted()
  }

  private def create(
      contractId: String,
      signatories: Seq[String] = Seq(aKeyMaintainer),
      argument: TransactionBuilder.Value = aDummyValue,
      keyAndMaintainer: Option[(String, String)] = Some("key" -> aKeyMaintainer),
  ): TransactionBuilder.Create =
    new TransactionBuilder().create(
      id = contractId,
      template = "dummyPackage:DummyModule:DummyTemplate",
      argument = argument,
      signatories = signatories,
      observers = Seq.empty,
      key = keyAndMaintainer.map { case (key, maintainer) =>
        TransactionBuilder.record(maintainer -> key)
      },
    )
}
