package kamon.instrumentation.play

import kamon.util.instrumentation.KamonInstrumentation
import net.bytebuddy.description.NamedElement
import net.bytebuddy.implementation.MethodDelegation._
import net.bytebuddy.implementation.bind.annotation.{RuntimeType, SuperCall}
import net.bytebuddy.matcher.ElementMatchers._

class LoggerLikeInstrumentation extends KamonInstrumentation {

  //forSubtypeOf("play.api.LoggerLike")

  forType {
    nameMatches[NamedElement]("play.api..*")
      .or(nameMatches[NamedElement]("kamon.play..*"))
      .and(isSubTypeOf(typePool.describe("play.api.LoggerLike").resolve())
      .and(not(isInterface())))
  }

    addTransformation { (builder, _) =>
      builder
        .method(named("info").or(named("debug").or(named("warn").or(named("error").or(named("trace"))))))
        .intercept(to(LoggerLikeInterceptor).filter(NotDeclaredByObject))
    }

    object LoggerLikeInterceptor {
      import kamon.trace.logging.MdcKeysSupport.withMdc

      @RuntimeType
      def log(@SuperCall r: Runnable): Unit = withMdc(r.run())
    }
}