package io.radicalbit.nsdb.web.client

import java.net.URI

import io.radicalbit.nsdb.web.actor.StreamActor.{RegisterQuery, RegisterQuid}
import org.java_websocket.client.WebSocketClient
import org.java_websocket.drafts.Draft_17
import org.java_websocket.handshake.ServerHandshake
import org.json4s.DefaultFormats
import org.json4s.jackson.Serialization.write

abstract class WSClient(url: String) extends WebSocketClient(new URI(url), new Draft_17()) {

  override def onMessage(message: String): Unit = println(message)

  override def onError(ex: Exception): Unit = println("Websocket Error: " + ex.getMessage)

  override def onClose(code: Int, reason: String, remote: Boolean): Unit = println("Websocket closed")

}

private class WSClientByQueryString(url: String, db: String, namespace: String, metric: String, queryString: String)
    extends WSClient(url) {

  override def onOpen(handshakedata: ServerHandshake): Unit = {

    implicit val formats = DefaultFormats

    println("Websocket opened")
    val registerQueryMessage = RegisterQuery(db, namespace, metric, queryString)
    send(write(registerQueryMessage))
  }

}

private class WSClientByQuid(url: String, db: String, namespace: String, metric: String, quid: String)
    extends WSClient(url) {

  override def onOpen(handshakedata: ServerHandshake): Unit = {

    implicit val formats = DefaultFormats

    println("Websocket opened")
    val registerQueryMessage = RegisterQuid(db, namespace, metric, quid)
    send(write(registerQueryMessage))
  }

}

object WSClient {
  def byQueryString(url: String, db: String, namespace: String, metric: String, queryString: String): WSClient = {
    new WSClientByQueryString(url, db, namespace, metric, queryString)
  }

  def byQueryId(url: String, db: String, namespace: String, metric: String, quid: String): WSClient = {
    new WSClientByQuid(url, db, namespace, metric, quid)
  }
}
