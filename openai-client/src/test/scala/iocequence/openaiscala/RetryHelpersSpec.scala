package iocequence.openaiscala

import akka.actor.{ActorSystem, Scheduler}
import akka.testkit.TestKit
import io.cequence.openaiscala.RetryHelpers.RetrySettings
import io.cequence.openaiscala.{
  OpenAIScalaClientException,
  OpenAIScalaClientTimeoutException,
  OpenAIScalaClientUnknownHostException,
  RetryHelpers
}
import org.mockito.scalatest.MockitoSugar
import org.scalatest.BeforeAndAfterAll
import org.scalatest.RecoverMethods._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}

class RetryHelpersSpec
    extends TestKit(ActorSystem("RetryHelpersSpec"))
    with AnyWordSpecLike
    with Matchers
    with BeforeAndAfterAll
    with MockitoSugar
    with ScalaFutures
    with RetryHelpers {

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }

  implicit val patience: PatienceConfig = PatienceConfig(timeout = 10.seconds)
  override def patienceConfig: PatienceConfig = patience

  "RetryHelpers" should {

    implicit val retrySettings: RetrySettings = RetrySettings()
    implicit val scheduler: Scheduler = actorSystem.scheduler
    val successfulResult = 42

    def testWithResults(attempts: Int, results: Seq[Future[Int]])(
        test: (Retryable, Future[Int]) => Unit
    ): Unit = {
      val future = Promise[Int]().future
      val mockRetryable = mock[Retryable]
      when(mockRetryable.attempt())
        .thenReturn(results.head, results.takeRight(results.length - 1): _*)
      val result = future.retry(() => mockRetryable.attempt(), attempts)
      test(mockRetryable, result)
    }

    def testWithException(ex: OpenAIScalaClientException)(
        test: (Retryable, Future[Int]) => Unit
    ): Unit = {
      val results = Seq(Future.failed(ex), Future.successful(successfulResult))
      testWithResults(results.length, results)(test)
    }

    def verifyNumAttempts[T](n: Int, f: Future[T], mock: Retryable): Unit =
      whenReady(f) { _ =>
        verify(mock, times(n)).attempt()
      }

    "retry when encountering a retryable failure" in {
      val attempts = 2
      val ex = new OpenAIScalaClientTimeoutException("retryable test exception")
      testWithException(ex) { (mockRetryable, result) =>
        result.futureValue shouldBe successfulResult
        verifyNumAttempts(n = attempts, result, mockRetryable)
      }
    }

    "not retry when encountering a non-retryable failure" in {
      val ex = new OpenAIScalaClientUnknownHostException(
        "non retryable test exception"
      )
      testWithException(ex) { (mockRetryable, result) =>
        val f = for {
          _ <- recoverToExceptionIf[OpenAIScalaClientUnknownHostException](
            result
          )
        } yield mockRetryable
        verifyNumAttempts(n = 1, f, mockRetryable)
      }
    }

    "not retry on success" in {
      testWithResults(attempts = 2, Seq(Future.successful(successfulResult))) {
        (mockRetryable, result) =>
          result.futureValue shouldBe successfulResult
          verifyNumAttempts(n = 1, result, mockRetryable)
      }
    }
  }

  override def actorSystem: ActorSystem = system
}

trait Retryable {
  def attempt(): Future[Int]
}