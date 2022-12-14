package service.process_twitch_chat.impl

import io.circe.*
import zio.*
import zio.stream.*

import cats.syntax.all.catsSyntaxSemigroup

import sttp.capabilities.WebSockets
import sttp.capabilities.zio.ZioStreams
import sttp.client3.*
import sttp.ws.WebSocketFrame
import sttp.ws.WebSocketFrame.*

import service.http_client.HttpClient
import service.process_twitch_chat.*
import service.authentication_store.AuthenticationStore

import model.*
import model.Auth.*
import model.AuxTypes.{Capabilities, Channel, JoinChannels}
import model.Outgoing.*

import common.*

import type_classes.instances.semigroup.given
import type_classes.instances.show.given

val authStream: ZStream[AuthenticationStore, Throwable, WebSocketFrame] =
  for
    info <- ZStream.fromZIO(AuthenticationStore.get)

    cap  = Auth.CAP_REQ(Capabilities.membership |+| Capabilities.tags |+| Capabilities.commands)
    pass = Auth.PASS(info.accessToken)
    nick = Outgoing.NICK(info.channels)
    join = Outgoing.JOIN(info.channels)

    frames <- ZStream(cap, pass, nick, join).map {
      case auth: Auth         => Auth.toFrame(auth)
      case outgoing: Outgoing => Outgoing.toFrame(outgoing)
    }
  yield frames

type Pipe[A, B] = ZStream[Any, Throwable, A] => ZStream[Any, Throwable, B]

def processFrames(f: ProcessIncoming): Pipe[WebSocketFrame, WebSocketFrame] =
  _.flatMap {
    case WebSocketFrame.Text(payload, _, _) =>
      for
        incoming <- ZStream(payload.split("\r\n")*)
        outgoing <- Incoming.parse(incoming) match
          case Left(value)  => printIgnore(value)
          case Right(value) => ZStream(value).viaFunction(f)
      yield Outgoing.toFrame(outgoing)

    case WebSocketFrame.Ping(payload) => ZStream(WebSocketFrame.Pong(payload))
    case other                        => printIgnore(other)
  }

def makeChatter(f: ProcessIncoming): RIO[HttpClient & AuthenticationStore, Response[Either[String, Unit]]] =
  for
    info <- ZIO.environment[AuthenticationStore]
    auth = authStream.provideEnvironment(info)
    response <- HttpClient.websocket(
      uri = uri"ws://irc-ws.chat.twitch.tv:80", // TODO: Switch to secure URI
      f = auth ++ _.viaFunction(processFrames(f)))
  yield response
