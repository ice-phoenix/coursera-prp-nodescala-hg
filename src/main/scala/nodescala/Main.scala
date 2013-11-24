package nodescala

import scala.language.postfixOps
import scala.concurrent._
import scala.concurrent.duration._
import ExecutionContext.Implicits.global
import scala.async.Async.{async, await}

object Main {

  def main(args: Array[String]) {
    val myServer = new NodeScala.Default(8191)
    val myServerSubscription = myServer.start("/test") {
      r => r.map(_.toString()).toIterator
    }

    Future.run() {
      ct => Future {
        var shouldStop = false
        while (ct.nonCancelled && !shouldStop) {
          val userInput = for {
            msg <- Future.userInput("# ")
          } yield msg match {
              case "stop" => {
                shouldStop = true
                myServerSubscription.unsubscribe()
              }
              case _ => {}
            }
          Await.ready(userInput, Duration.Inf)
        }
      }
    }

  } // def main

}
