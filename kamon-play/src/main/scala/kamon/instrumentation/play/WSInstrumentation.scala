package kamon.instrumentation.play

import java.util.concurrent.Callable

import kamon.play.PlayExtension
import kamon.trace.{SegmentCategory, Tracer}
import kamon.util.SameThreadExecutionContext
import kamon.util.instrumentation.KamonInstrumentation
import net.bytebuddy.implementation.MethodDelegation._
import net.bytebuddy.implementation.bind.annotation.{SuperCall, This}
import net.bytebuddy.matcher.ElementMatchers._
import play.api.libs.ws.{WSRequest, WSResponse}

import scala.concurrent.Future

class WSInstrumentation extends KamonInstrumentation {

  forTargetType("play.api.libs.ws.ning.NingWSRequest")

  addTransformation((builder, _) ⇒ builder.method(named("execute").and(NotTakesArguments)).intercept(to(WSInterceptor)))

  object WSInterceptor {
    def execute(@SuperCall callable: Callable[Future[WSResponse]], @This request: WSRequest): Future[WSResponse] = {
      Tracer.currentContext.collect { ctx ⇒
        val segmentName = PlayExtension.generateHttpClientSegmentName(request)
        val segment = ctx.startSegment(segmentName, SegmentCategory.HttpClient, PlayExtension.SegmentLibraryName)
        val response = callable.call()

        response.onComplete(result ⇒ segment.finish())(SameThreadExecutionContext)
        response
      } getOrElse callable.call()
    }
  }
}