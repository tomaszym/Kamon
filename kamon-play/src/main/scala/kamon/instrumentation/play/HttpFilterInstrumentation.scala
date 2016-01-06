/* =========================================================================================
 * Copyright © 2013-2015 the kamon project <http://kamon.io/>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 * =========================================================================================
 */

package kamon.instrumentation.play

import java.util.concurrent.Callable

import kamon.Kamon.tracer
import kamon.play.PlayExtension
import kamon.trace._
import kamon.util.{initializer, SameThreadExecutionContext}
import kamon.util.instrumentation.KamonInstrumentation
import net.bytebuddy.implementation.MethodDelegation._
import net.bytebuddy.implementation.bind.annotation.{Argument, RuntimeType, SuperCall, This}
import net.bytebuddy.matcher.ElementMatchers._
import play.api.mvc.Results._
import play.api.mvc._

import scala.concurrent.Future


class RequestHeaderToTraceContextAware extends KamonInstrumentation {

  forType {
    isSubTypeOf(typePool.describe("play.api.mvc.RequestHeader").resolve()).and(not(isInterface()))
  }

  addMixin(classOf[InjectTraceContext])

  trait InjectTraceContext extends TraceContextAware
}


class HttpRequestHandlerInstrumentation extends KamonInstrumentation {

  forType(named("play.api.http.DefaultHttpRequestHandler"))

  addTransformation((builder, _) => builder.method(named("routeRequest")).intercept(to(HttpRequestHandlerInterceptor)))//.filter(NotDeclaredByObject)))

  object HttpRequestHandlerInterceptor {
    @RuntimeType
    def routeRequest(requestHeader: RequestHeader, @SuperCall r: Callable[_]):Any  = {
      val token = if (PlayExtension.includeTraceToken) {
        requestHeader.headers.get(PlayExtension.traceTokenHeaderName)
      } else None

      Tracer.setCurrentContext(tracer.newContext("UnnamedTrace", token))
      //TODO:improve this!!!!
      r.call()
    }
  }
}

class HttpFilterInstrumentation extends KamonInstrumentation {

  forType {
    isSubTypeOf(typePool.describe("play.api.http.HttpFilters").resolve()).and(not(isInterface()))
  }

  addTransformation { (builder, typeDescription) =>
    builder.method(named("filters")).intercept(to(FiltersInterceptor))
  }

  object FiltersInterceptor {
    @RuntimeType
    def routeRequest(@SuperCall c: Callable[_]): Any= {
      val filter = new EssentialFilter {
        def apply(next: EssentialAction) = EssentialAction((requestHeader) ⇒ {
          def onResult(result: Result): Result = {
            Tracer.currentContext.collect { ctx ⇒
              ctx.finish()
              PlayExtension.httpServerMetrics.recordResponse(ctx.name, result.header.status.toString)

              if (PlayExtension.includeTraceToken) result.withHeaders(PlayExtension.traceTokenHeaderName -> ctx.token)
              else result

            } getOrElse result
          }
          //override the current trace name
          Tracer.currentContext.rename(PlayExtension.generateTraceName(requestHeader))
          // Invoke the action
          next(requestHeader).map(onResult)(SameThreadExecutionContext)
        })
      }
      c.call().asInstanceOf[Seq[EssentialFilter]] :+ filter
    }
  }
}

//class HttpErrorHandlerInstrumentation extends KamonInstrumentation {
//
//    forSubtypeOf("play.api.http.HttpErrorHandler")
//
//    addTransformation { (builder, typeDescription) =>
//      builder
//        .method(named("onClientError")).intercept(to(ClientErrorInterceptor))//.filter(NotDeclaredByObject))
//        .method(named("onServerError")).intercept(to(ServerErrorInterceptor))//.filter(NotDeclaredByObject))
//    }
//
//  object ClientErrorInterceptor {
//    @RuntimeType
//    def onClientError(requestContextAware: RequestHeader, statusCode: Int,  message: String, @SuperCall r: Callable[_]): Any = {
//      requestContextAware.asInstanceOf[TraceContextAware].traceContext.collect { ctx ⇒
//        PlayExtension.httpServerMetrics.recordResponse(ctx.name, statusCode.toString)
//      }
//      r.call()
//    }
//  }
//
//  object ServerErrorInterceptor {
//    @RuntimeType
//    def onServerError(requestContextAware: RequestHeader, ex: Throwable, @SuperCall r: Callable[_]): Any = {
//      requestContextAware.asInstanceOf[TraceContextAware].traceContext.collect { ctx ⇒
//        PlayExtension.httpServerMetrics.recordResponse(ctx.name, InternalServerError.header.status.toString)
//      }
//    r.call()
//    }
//  }
//}
