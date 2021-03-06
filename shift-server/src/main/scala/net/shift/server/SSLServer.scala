package net.shift.server

import java.io.FileInputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.{SelectionKey, Selector, ServerSocketChannel, SocketChannel}
import java.security.KeyStore
import java.util.concurrent.Executors
import javax.net.ssl._

import net.shift.common.{Config, LogBuilder}
import net.shift.io.IO
import net.shift.server.Selections._
import net.shift.server.protocol.ProtocolBuilder

import scala.annotation.tailrec
import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object SSLServer {
  def apply() = new SSLServer(SSLServerSpecs())
}

case class SSLServer(specs: SSLServerSpecs) extends SSLOps {


  private val selector = Selector.open

  private val clients = new TrieMap[SelectionKey, SSLClientHandler]

  @volatile
  private var running = false

  def start(protocol: ProtocolBuilder): Future[Unit] = {

    implicit val ctx = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(specs.numThreads))

    @tailrec
    def loop(serverChannel: ServerSocketChannel) {
      if (running) {

        selector.select()

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

                  val sslEngine = makeSSLEngine()
                  sslEngine.beginHandshake()

                  if (handshake(client, sslEngine)) {
                    val clientKey = client.register(selector, SelectionKey.OP_READ)
                    val clientName = client.getRemoteAddress.toString + "-" + clientKey
                    log.info("Accepted connection " + clientName)
                    clients.put(clientKey, new SSLClientHandler(clientKey, sslEngine, clientName, k => {
                      closeClient(k)
                    }, protocol.createProtocol))
                  }
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
      try {
        loop(serverChannel)
      } catch {
        case t: Throwable => t.printStackTrace()
      }
    }

    listen.map { _ =>
      log.info("Shutting down server")
      serverChannel.close()
    }
  }


  private def handshake(socket: SocketChannel, engine: SSLEngine): Boolean = {
    // https://github.com/alkarn/sslengine.example/tree/master/src/main/java/alkarn/github/io/sslengine/example
    val appBufferSize = engine.getSession.getApplicationBufferSize
    val packetSize = engine.getSession.getPacketBufferSize

    var clientDecryptedData = ByteBuffer.allocate(appBufferSize)
    var clientEncryptedData = ByteBuffer.allocate(packetSize)

    val serverDecryptedData = ByteBuffer.allocate(appBufferSize)
    val serverEncryptedData = ByteBuffer.allocate(packetSize)

    var handshakeStatus = engine.getHandshakeStatus

    var forceDone = false
    while (handshakeStatus != SSLEngineResult.HandshakeStatus.FINISHED && handshakeStatus != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING && !forceDone) {
      log.debug("HS Status " + handshakeStatus)
      handshakeStatus match {
        case SSLEngineResult.HandshakeStatus.NEED_UNWRAP =>
          val read = socket.read(clientEncryptedData)
          log.debug("HS Read " + read + " bytes")
          if (read < 0) {
            if (engine.isInboundDone && engine.isOutboundDone) {
              return false
            } else {
              try {
                engine.closeInbound()
              } catch {
                case e: Throwable =>
                  log.error("This engine was forced to close inbound, without having received the proper SSL/TLS close notification message from the peer, due to end of stream.");
              }
              engine.closeOutbound()
            }
          }

          clientEncryptedData.flip()

          unwrap(engine, clientEncryptedData, clientDecryptedData) match {
            case OPResult(SSLEngineResult.Status.BUFFER_UNDERFLOW, src, _) =>
              clientEncryptedData = src
            case OPResult(SSLEngineResult.Status.BUFFER_OVERFLOW, _, dest) =>
              clientDecryptedData = dest
            case OPResult(SSLEngineResult.Status.CLOSED, _, dest) =>
              if (engine.isOutboundDone()) {
                return false;
              } else {
                engine.closeOutbound()
              }
            case _ =>
          }

          clientEncryptedData.compact()

        case SSLEngineResult.HandshakeStatus.NEED_WRAP =>
          serverEncryptedData.clear()
          wrap(engine, serverDecryptedData, serverEncryptedData) match {
            case OPResult(SSLEngineResult.Status.OK, out, _) =>
              out.flip()
              println("Sending " + out)
              while (out.hasRemaining()) {
                socket.write(out)
              }
              println("Sent " + out)
            case OPResult(SSLEngineResult.Status.CLOSED, out, _) =>
              out.flip()
              println("Sending " + out)
              while (out.hasRemaining()) {
                socket.write(out)
              }
              engine.closeOutbound()
              forceDone = true
              println("Sent " + out)

          }

        case SSLEngineResult.HandshakeStatus.NEED_TASK =>
          val exec = Executors.newSingleThreadExecutor()
          var task = engine.getDelegatedTask
          while (task != null) {
            exec.execute(task)
            task = engine.getDelegatedTask
          }

        case _ =>
      }
      handshakeStatus = engine.getHandshakeStatus
    }

    (handshakeStatus == SSLEngineResult.HandshakeStatus.FINISHED || handshakeStatus == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) && !forceDone
  }

  private def makeSSLEngine(): SSLEngine = {
    val ks = KeyStore.getInstance("JKS")
    val ts = KeyStore.getInstance("JKS")

    val passPhrase = specs.pass.toCharArray
    ks.load(new FileInputStream(specs.keyStoreFile), passPhrase)
    ts.load(new FileInputStream(specs.trustStoreFile), passPhrase)

    val kmf = KeyManagerFactory.getInstance("SunX509")
    kmf.init(ks, passPhrase)

    val tmf = TrustManagerFactory.getInstance("SunX509")
    tmf.init(ts)

    val sslCtx = SSLContext.getInstance("TLS")

    sslCtx.init(kmf.getKeyManagers, tmf.getTrustManagers, null)

    val engine = sslCtx.createSSLEngine()
    engine.setUseClientMode(false)
    engine.setNeedClientAuth(false)
    engine
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

object SSLServerSpecs {
  def apply(): SSLServerSpecs = fromConfig(Config())

  def fromConfig(conf: Config): SSLServerSpecs = {
    SSLServerSpecs(
      name = conf.string("server.name", "Shift-HTTPServer"),
      address = conf.string("server.address", "0.0.0.0"),
      port = conf.int("server.port", 8080),
      numThreads = conf.int("server.numThreads", Runtime.getRuntime.availableProcessors()),
      keyStoreFile = conf.string("server.keystore", "keystore.jks"),
      trustStoreFile = conf.string("server.truststore", "truststore.ts"),
      pass = conf.string("server.pass"))
  }
}

case class SSLServerSpecs(name: String,
                          address: String,
                          port: Int,
                          numThreads: Int,
                          keyStoreFile: String,
                          trustStoreFile: String,
                          pass: String)


