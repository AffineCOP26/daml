// Copyright (c) 2020 The DAML Authors. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.platform.sandbox.services.reset

import com.digitalasset.platform.sandbox.services.SandboxFixture

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.ref.WeakReference

final class ResetServiceIT extends ResetServiceITBase with SandboxFixture {
  "ResetService" when {
    "clear out all garbage" in {
      val state = new WeakReference(Await.result(server.sandboxState, 5.seconds))
      for {
        lid <- fetchLedgerId()
        _ <- reset(lid)
      } yield {
        System.gc()
        state.get should be(None)
      }
    }
  }
}
