package net.shift.server

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.{SelectionKey, Selector, ServerSocketChannel}
import java.util.concurrent.Executors

import net.shift.common.{Config, LogBuilder}
import net.shift.io.IO
import net.shift.server.Selections._
import net.shift.server.http.Payload
import net.shift.server.protocol.ProtocolBuilder

import scala.annotation.tailrec
import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}

object Server {
  def apply() = new Server(ServerSpecs())
}

case class Server(specs: ServerSpecs) {

  private val log = LogBuilder.logger(classOf[Server])

  private val selector = Selector.open

  private val clients = new TrieMap[SelectionKey, ClientHandler]

  @volatile
  private var running = false

  def start(protocol: ProtocolBuilder): Future[Unit] = {

    implicit val ctx = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(specs.numThreads))

    @tailrec
    def loop(serverChannel: ServerSocketChannel) {
      if (running) {

        val r = selector.select()

        val keys = selector.selectedKeys().iterator()

        while (keys.hasNext) {
          val key = keys.next()
          keys.remove()

          if (!running) {
            closeClient(key)
          } else {
            if (key.isValid) {
              if (key.isAcceptable) {
                val client = serverChannel.accept()
                if (client != null) {
                  client.configureBlocking(false)
                  val clientKey = client.register(selector, SelectionKey.OP_READ)
                  val clientName = client.getRemoteAddress.toString + "-" + clientKey
                  log.info("Accepted connection " + clientName)
                  clients.put(clientKey, new ClientHandler(clientKey, clientName, k => {
                    closeClient(k)
                  }, protocol.createProtocol))
                }
              } else if (key.isReadable) {
                clients.get(key).foreach {
                  _.readChunk
                }
              } else if (key.isWritable) {
                unSelectForWrite(key)
                clients.get(key).foreach {
                  _.continueWriting()
                }
              }
            }
          }
        }
        loop(serverChannel)
      }
    }

    val serverChannel = ServerSocketChannel.open()
    serverChannel.configureBlocking(false)
    val address = new InetSocketAddress(specs.address, specs.port)
    serverChannel.bind(address)
    log.info("Server bound to " + address)

    serverChannel.register(selector, SelectionKey.OP_ACCEPT, null)

    running = true

    val listen = Future {
      loop(serverChannel)
    }

    listen.map { _ =>
      log.info("Shutting down server")
      serverChannel.close()
    }
  }

  private def closeClient(key: SelectionKey) {
    IO.close(key.channel())
    key.cancel()
    val state = clients remove key
    log.info(s"Client $key removed: $state")
  }

  def stop(): Selector = {
    running = false
    selector.wakeup()
  }

}

object RawExtract {
  def unapply(t: Option[Payload]): Option[Raw] = t match {
    case None => Some(Raw(Nil))
    case Some(raw: Raw) => Some(raw)
    case _ => None
  }
}

case class Raw(buffers: List[ByteBuffer]) extends Payload {
  def +(b: ByteBuffer) = Raw(buffers ++ List(b))

  def ++(b: Seq[ByteBuffer]) = Raw(buffers ++ b)

  def size: Int = buffers map {
    _.limit
  } sum

  def buffersState: String = buffers map { b => s"${b.position} : ${b.limit}" } mkString "\n"

  def duplicates: List[ByteBuffer] = buffers map {
    _ duplicate
  }
}

object ServerSpecs {
  def apply(): ServerSpecs = fromConfig(Config())

  def fromConfig(conf: Config): ServerSpecs = {
    ServerSpecs(
      name = conf.string("server.name", "Shift-HTTPServer"),
      address = conf.string("server.address", "0.0.0.0"),
      port = conf.int("server.port", 8080),
      numThreads = conf.int("server.numThreads", Runtime.getRuntime.availableProcessors()))
  }
}

case class ServerSpecs(name: String,
                       address: String,
                       port: Int,
                       numThreads: Int)


