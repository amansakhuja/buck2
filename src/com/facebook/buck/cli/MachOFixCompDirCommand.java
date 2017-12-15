/*
 * Copyright 2016-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.facebook.buck.cli;

import com.facebook.buck.macho.CompDirReplacer;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.charset.NulTerminatedCharsetDecoder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class MachOFixCompDirCommand extends MachOAbstractCommand {

  @Override
  protected ExitCode invokeWithParams(CommandRunnerParams params) throws IOException {
    NulTerminatedCharsetDecoder decoder =
        new NulTerminatedCharsetDecoder(StandardCharsets.UTF_8.newDecoder());
    CompDirReplacer.replaceCompDirInFile(
        getOutput(), getOldCompDir(), getUpdatedCompDir(), decoder);
    return ExitCode.SUCCESS;
  }

  @Override
  public String getShortDescription() {
    return "fixes compilation directory inside Mach O binary";
  }
}
