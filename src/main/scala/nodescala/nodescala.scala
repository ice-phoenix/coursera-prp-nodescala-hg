package nodescala

import com.sun.net.httpserver._
import scala.concurrent._
import scala.concurrent.duration._
import ExecutionContext.Implicits.global
import scala.async.Async.{async, await}
import scala.collection._
import scala.collection.JavaConversions._
import scala.util.{Try, Success, Failure}
import java.util.concurrent.{Executor, ThreadPoolExecutor, TimeUnit, LinkedBlockingQueue}
import java.net.InetSocketAddress

/** Contains utilities common to the NodeScala© framework.
  */
trait NodeScala {
  self =>

  import NodeScala._

  def port: Int

  def createListener(relativePath: String): Listener

  /** Writes the response to a given exchange.
    * The response should be written back in parts, and the method should
    * occasionally check that the server was not stopped, otherwise a very long
    * response may take too long to finish.
    *
    * @param exchange   the exchange used to write the response back
    * @param token      the cancellation token
    * @param response   the response to write back
    */
  private def respond(exchange: Exchange, token: CancellationToken, response: Response): Unit = {
    try {
      while (token.nonCancelled && response.hasNext) {
        exchange.write(response.next())
      }
    } finally {
      exchange.close()
    }
  }

  /** A server:
    * 1) creates and starts an http listener
    * 2) creates a cancellation token (hint: use one of the `Future` companion methods)
    * 3) as long as the token is not cancelled and there is a request from the http listener
    *    asynchronously process that request using the `respond` method
    *
    * @param relativePath   a relative path which to start listening on
    * @param handler        a function mapping a request to a response
    * @return               a subscription that can stop the server and all its asynchronous operations *completely*
    */
  def start(relativePath: String)(handler: Request => Response): Subscription = {
    val l = createListener(relativePath)
    Subscription(
      l.start(),
      Future.run() {
        ct => Future {
          while (ct.nonCancelled) {
            val nextResult = l.nextRequest().map { req => self.respond(req._2, ct, handler(req._1)) }
            Await.ready(nextResult, Duration.Inf)
          }
        }
      }
    )
  }

}


object NodeScala {

  /** A request is a multimap of string headers.
    */
  type Request = Map[String, List[String]]

  /** A response is a potentially very long string (e.g., a data file).
    * To be able to process this string in parts, the response is encoded
    * as an iterator over parts of the response string.
    */
  type Response = Iterator[String]

  /** An exchange is used to write a response to a request.
    */
  trait Exchange {
    /** Writes to the output stream of the exchange.
      */
    def write(s: String): Unit

    /** Signals that the response has ended and that there
      * will be no further writes.
      */
    def close(): Unit

    def request: Request

  }

  object Exchange {
    def apply(exchange: HttpExchange) = new Exchange {
      val os = exchange.getResponseBody()
      exchange.sendResponseHeaders(200, 0L)

      def write(s: String) = os.write(s.getBytes)

      def close() = os.close()

      def request: Request = {
        val headers = for ((k, vs) <- exchange.getRequestHeaders) yield (k, vs.toList)
        immutable.Map() ++ headers
      }
    }
  }

  trait Listener {
    def port: Int

    def relativePath: String

    def start(): Subscription

    def createContext(handler: Exchange => Unit): Unit

    def removeContext(): Unit

    /** Given a relative path:
      * 1) constructs an uncompleted promise
      * 2) installs an asynchronous request handler using `createContext`
      *    that completes the promise with a request when it arrives
      *    and then unregisters itself using `removeContext`
      * 3) returns the future with the request
      *
      * @return   a promise holding (Request, Exchange) pair
      */
    def nextRequest(): Future[(Request, Exchange)] = {
      val p = Promise[(Request, Exchange)]()
      createContext(h => {
        p.complete(Try(h.request, h))
        removeContext()
      })
      p.future
    }
  }

  object Listener {

    class Default(val port: Int, val relativePath: String) extends Listener {
      private val s = HttpServer.create(new InetSocketAddress(port), 0)
      private val executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue)
      s.setExecutor(executor)

      def start() = {
        s.start()
        new Subscription {
          def unsubscribe() = {
            s.stop(0)
            executor.shutdown()
          }
        }
      }

      def createContext(handler: Exchange => Unit) = s.createContext(relativePath, new HttpHandler {
        def handle(httpxchg: HttpExchange) = handler(Exchange(httpxchg))
      })

      def removeContext() = s.removeContext(relativePath)
    }

  }

  /** The default server implementation.
    */
  class Default(val port: Int) extends NodeScala {
    def createListener(relativePath: String) = new Listener.Default(port, relativePath)
  }

}
