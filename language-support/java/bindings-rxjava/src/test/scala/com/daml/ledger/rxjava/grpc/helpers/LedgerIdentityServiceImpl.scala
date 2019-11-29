// Copyright (c) 2019 The DAML Authors. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.rxjava.grpc.helpers

import com.digitalasset.ledger.api.auth.Authorizer
import com.digitalasset.ledger.api.auth.services.LedgerIdentityServiceAuthorization
import com.digitalasset.ledger.api.v1.ledger_identity_service.{
  GetLedgerIdentityRequest,
  GetLedgerIdentityResponse,
  LedgerIdentityServiceGrpc
}
import com.digitalasset.ledger.api.v1.ledger_identity_service.LedgerIdentityServiceGrpc.LedgerIdentityService
import io.grpc.ServerServiceDefinition

import scala.concurrent.{ExecutionContext, Future}

final class LedgerIdentityServiceImpl(ledgerId: String)
    extends LedgerIdentityService
    with FakeAutoCloseable {

  override def getLedgerIdentity(
      request: GetLedgerIdentityRequest): Future[GetLedgerIdentityResponse] = {
    Future.successful(GetLedgerIdentityResponse(ledgerId))
  }
}

object LedgerIdentityServiceImpl {

  def createWithRef(ledgerId: String, authorizer: Authorizer)(
      implicit ec: ExecutionContext): (ServerServiceDefinition, LedgerIdentityServiceImpl) = {
    val impl = new LedgerIdentityServiceImpl(ledgerId)
    val authImpl = new LedgerIdentityServiceAuthorization(impl, authorizer)
    (LedgerIdentityServiceGrpc.bindService(authImpl, ec), impl)
  }
}
