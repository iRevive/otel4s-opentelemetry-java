/*
 */

package io.opentelemetry.javaagent.instrumentation.catseffect.common.v3_6;

import application.io.opentelemetry.context.Context;
import cats.effect.IOLocal;
import io.opentelemetry.context.ContextStorage;
import io.opentelemetry.context.Scope;
import io.opentelemetry.javaagent.instrumentation.opentelemetryapi.v1_0.context.AgentContextStorage;
import java.util.logging.Logger;

public class IoLocalContextSingleton {

  private static final Logger logger = Logger.getLogger(IoLocalContextSingleton.class.getName());

  private IoLocalContextSingleton() {}

  public static final IOLocal<Context> ioLocal =
      IOLocal.apply(Context.root()).syncStep(100).unsafeRunSync().toOption().get();

  private static final ThreadLocal<Context> ioLocalThreadLocal = ioLocal.unsafeThreadLocal();

  public static final ContextStorage contextStorage =
      new ContextStorage() {
        @Override
        public io.opentelemetry.context.Context current() {
          Context current = ioLocalThreadLocal.get();
          return current != null ? AgentContextStorage.getAgentContext(current) : null;
        }

        @Override
        public Scope attach(io.opentelemetry.context.Context toAttach) {
          if (toAttach == null || toAttach == current()) {
            return Scope.noop();
          }
          Context beforeAttach = ioLocalThreadLocal.get();
          Context attached = AgentContextStorage.toApplicationContext(toAttach);
          ioLocalThreadLocal.set(attached);
          return new ScopeImpl(beforeAttach, attached);
        }
      };

  private static class ScopeImpl implements Scope {
    private final Context beforeAttach;
    private final Context attached;
    private boolean closed;

    private ScopeImpl(Context beforeAttach, Context attached) {
      this.beforeAttach = beforeAttach;
      this.attached = attached;
    }

    @Override
    public void close() {
      // Agent/application context conversion need not preserve object identity. Compare the
      // application context actually stored in IOLocal, without converting it back to the agent
      // API.
      if (!closed && ioLocalThreadLocal.get() == attached) {
        closed = true;
        if (beforeAttach != null) {
          ioLocalThreadLocal.set(beforeAttach);
        } else {
          ioLocalThreadLocal.remove();
        }
      } else {
        logger.fine(
            "Trying to close scope which does not represent current context. Ignoring the call.");
      }
    }
  }
}
