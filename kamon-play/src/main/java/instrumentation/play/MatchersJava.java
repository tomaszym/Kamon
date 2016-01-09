package instrumentation.play;

import kamon.util.Supplier;
import kamon.util.instrumentation.KamonInstrumentation;
import kamon.util.instrumentation.listener.InstrumentationListener;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import net.bytebuddy.implementation.bind.annotation.SuperCall;
import net.bytebuddy.matcher.ElementMatcher;

import java.lang.instrument.Instrumentation;
import java.util.concurrent.Callable;

import static kamon.trace.logging.MdcKeysSupport$.MODULE$;
import static net.bytebuddy.matcher.ElementMatchers.*;

public class MatchersJava extends KamonInstrumentation {

    public ElementMatcher matcher() {
      return nameMatches("play.api..*").and(not(nameContains("$"))).and(isSubTypeOf(typePool().describe("play.api.LoggerLike").resolve()).and(not(TypeDescription::isInterface)));
    }

    @Override
    public void register(Instrumentation instrumentation) {
        new AgentBuilder.Default()
                .withListener(new InstrumentationListener())
                .type(matcher())
                .transform((builder, typeDescription) -> builder.method(named("info").or(named("debug").or(named("warn").or(named("error").or(named("trace"))))))
                        .intercept(MethodDelegation.to(new LoggerLikeInterceptor()))).installOn(instrumentation);
    }

     public static class LoggerLikeInterceptor {
        @RuntimeType
        public void log(@SuperCall Callable<?> zuper) {
            MODULE$.withMdc((Supplier<Object>) () -> {
                try {
                    return zuper.call();
                } catch (Exception e) {
                    e.printStackTrace();
                }
                return null;
            });
            }
        }
    }