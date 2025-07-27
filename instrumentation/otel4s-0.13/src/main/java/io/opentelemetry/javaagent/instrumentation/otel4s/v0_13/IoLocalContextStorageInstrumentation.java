/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.otel4s.v0_13;

import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;

import application.io.opentelemetry.context.Context;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import io.opentelemetry.javaagent.instrumentation.catseffect.common.v3_6.IoLocalContextSingleton;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public class IoLocalContextStorageInstrumentation implements TypeInstrumentation {

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("org.typelevel.otel4s.oteljava.context.IOLocalContextStorage$");
  }

  @Override
  public void transform(TypeTransformer transformer) {
    transformer.applyAdviceToMethod(
        isMethod().and(named("getAgentLocalContext")),
        this.getClass().getName() + "$GetAgentLocalContextAdvice");
  }

  @SuppressWarnings("unused")
  public static final class GetAgentLocalContextAdvice {

    private GetAgentLocalContextAdvice() {}

    @Advice.OnMethodExit
    public static void methodExit(
        @Advice.Return(readOnly = false) scala.Option<cats.effect.IOLocal<Context>> result) {
      result = scala.Option.apply(IoLocalContextSingleton.ioLocal);
    }
  }
}
