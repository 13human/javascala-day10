package academy.javascala

object _0Lesson8 extends App {

  import akka.actor.{Actor, ActorSystem, PoisonPill, Props}
  import akka.util.Timeout

  import scala.concurrent.{Await, Future}

  val system = ActorSystem("lesson8")

  val actor = system.actorOf(Props(classOf[ExampleActor], "abc"), "example1")

  actor ! RunLogic("logic1", 5000)
  actor ! RunLogic("logic2", 1000)

  import akka.pattern.ask

  import scala.concurrent.duration._

  implicit val timeout: Timeout = 1.second
  val resultFuture = actor ? AskLogic("logic3", 100)
  println("result is: " + Await.result(resultFuture, 1.second))

  actor ! PoisonPill

  //---------------------------

  case class RunLogic(name: String, duration: Long)

  case class AskLogic(name: String, duration: Long)

  class ExampleActor(str: String) extends Actor {

    override def postStop(): Unit = {
      println("good bye")
    }

    override def receive: Receive = {
      case msg@RunLogic(name, duration) =>
        //println(s"staring $name")
        //Thread.sleep(duration)
        //println(s"done $name")
        val worker = context.actorOf(Props(classOf[WorkerExampleActor]))
        println("worker path =", worker.path)
        context.actorOf(Props(classOf[WorkerExampleActor])) ! msg

      case AskLogic(name, duration) =>
        import akka.pattern.pipe
        implicit val ec = context.dispatcher
        executeLogic(name).pipeTo(sender())

      case ("done", logicName) =>
        println(s"worker has finished $logicName")
      //sender() ! PoisonPill
    }


    private def executeLogic(name: String): Future[String] = {
      Future.successful(s"logic $name has been executed")
    }
  }


  class WorkerExampleActor extends Actor {


    override def postStop(): Unit = {
      println("worker terminated")
    }

    override def receive: Receive = {
      case RunLogic(name, duration) =>
        println(s"staring $name")
        Thread.sleep(duration)
        println(s"done $name")
        sender() ! ("done", name)

      //context stop self
    }
  }
}

object _0Lesson8Typed extends App {

  import academy.javascala._0Lesson8Typed.ExampleActorGuardian.SystemCmd
  import akka.actor.typed.scaladsl.AskPattern._
  import akka.actor.typed.scaladsl.Behaviors
  import akka.actor.typed.{ActorRef, ActorSystem, Behavior, PostStop}
  import akka.util.Timeout
  import scala.concurrent.{Await, Future}
  import scala.concurrent.duration._

  val system: ActorSystem[ExampleActorGuardian.SystemCmd] = ActorSystem(ExampleActorGuardian.apply("example1"), "lesson8")

  system ! ExampleActorGuardian.RunLogic("logic1", 5000)
  system ! ExampleActorGuardian.RunLogic("logic2", 1000)


  implicit val timeout: Timeout = 1.second
  val resultFuture: Future[String] = system.ask(ref => ExampleActorGuardian.AskLogic("logic3", 100, ref))(timeout, system.scheduler)
  println("result is: " + Await.result(resultFuture, 1.second))

  system ! ExampleActorGuardian.Stop

  //---------------------------


  object ExampleActorGuardian {
    sealed trait SystemCmd

    case class AskLogic(name: String, duration: Long, sender: ActorRef[String]) extends SystemCmd

    case class RunLogic(name: String, duration: Long) extends SystemCmd

    case class LogicResult(value: (String, String)) extends SystemCmd

    case object Stop extends SystemCmd

    private def executeLogic(name: String): Future[String] = {
      Future.successful(s"logic $name has been executed")
    }

    def apply(str: String): Behavior[SystemCmd] =
      Behaviors.setup { context =>

        Behaviors.receiveMessage[SystemCmd] {
          case message: RunLogic =>
            val worker = context.spawnAnonymous(WorkerExampleActor.apply())
            println("worker path =", worker.path)
            worker ! WorkerExampleActor.RunLogic(message.name, message.duration, context.self)
            Behaviors.same
          case message: AskLogic =>
            executeLogic(message.name).foreach(result => message.sender ! result)(context.executionContext)
            Behaviors.same
          case message: LogicResult =>
            println(s"worker has finished ${message.value._2}")
            Behaviors.same
          case Stop =>
            Behaviors.stopped
        }
          .receiveSignal {
            case (_, PostStop) =>
              println("good bye")
              Behaviors.same
          }
      }
  }


  object WorkerExampleActor {
    case class RunLogic(name: String, duration: Long, sender: ActorRef[ExampleActorGuardian.LogicResult]) extends SystemCmd

    def apply(): Behavior[RunLogic] = Behaviors
      .receive[RunLogic] { (context, message) =>
        val name = message.name
        val duration = message.duration
        println(s"staring $name")
        Thread.sleep(duration)
        println(s"done $name")
        message.sender ! ExampleActorGuardian.LogicResult(("done", name))
        Behaviors.stopped
      }
  }
}
