// Copyright (c) 2021 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.http

import com.typesafe.scalalogging.StrictLogging

trait CliBase extends StrictLogging {
  private[http] def parseConfig(
      args: collection.Seq[String],
      getEnvVar: String => Option[String] = sys.env.get,
  ): Option[Config] =
    configParser(getEnvVar).parse(args, Config.Empty)

  protected def configParser(getEnvVar: String => Option[String]): OptionParser

}
