package bffclient

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.scalajs.js

import io.circe.{Decoder, Encoder}
import io.circe.parser.decode
import menus.data.APIResponse

final class HttpError(message: String) extends Exception(message)

/** JSON over HTTP, with the session cookie of the menu server.
  *
  * Requests run on a LÖVE thread; their responses are polled every frame by a timer, which
  * completes the corresponding futures.
  */
final class HttpClient(baseUrl: String)(using ExecutionContext) {
  private var cookie  = ""
  private val pending = mutable.Map.empty[Int, Promise[HttpResponse]]
  private var poller: Option[js.timers.SetIntervalHandle] = None

  def sessionCookie: String = cookie

  private def poll(): Unit = {
    var response = HttpModule.poll()
    while response != null do {
      if (response.setCookie != null && response.setCookie.nonEmpty)
        cookie = response.setCookie.takeWhile(_ != ';')
      pending.remove(response.id).foreach(_.success(response))
      response = HttpModule.poll()
    }
    if pending.isEmpty then {
      poller.foreach(js.timers.clearInterval)
      poller = None
    }
  }

  private def request(method: String, path: String, body: String): Future[String] = {
    val promise = Promise[HttpResponse]()
    pending(HttpModule.request(method, baseUrl + path, body, cookie)) = promise
    if (poller.isEmpty) poller = Some(js.timers.setInterval(1)(poll()))
    promise.future.flatMap { response =>
      if (response.status == 200) Future.successful(response.body)
      else if (response.status == 0)
        Future.failed(HttpError(s"$method $path failed: ${response.error}"))
      else Future.failed(HttpError(s"$method $path: HTTP ${response.status} ${response.body}"))
    }
  }

  private def decoded[B: Decoder](body: String): Future[B] =
    Future.fromTry(
      decode[B](body).left.map(e => HttpError(s"Cannot decode $body: ${e.getMessage}")).toTry
    )

  def get[B: Decoder](path: String): Future[B] =
    request("GET", path, "").flatMap(decoded[B])

  /** POST without response body. */
  def post[A: Encoder](path: String, data: A): Future[Unit] =
    request("POST", path, Encoder[A].apply(data).noSpaces).map(_ => ())

  /** POST returning an `APIResponse[B]`, the menu server's usual answer. */
  def postApi[A: Encoder, B](path: String, data: A)(using Encoder[B], Decoder[B]): Future[B] =
    request("POST", path, Encoder[A].apply(data).noSpaces)
      .flatMap(decoded[APIResponse[B]])
      .flatMap(response =>
        Future.fromTry(response.toEither.left.map(error => HttpError(s"$path: $error")).toTry)
      )
}
