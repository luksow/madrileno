package madrileno.auth.repositories

import cats.effect.testing.scalatest.AsyncIOSpec
import madrileno.auth.domain.*
import madrileno.support.{TestData, TestTransactor}
import madrileno.user.domain.UserId
import madrileno.user.repositories.UserRepository
import madrileno.utils.db.transactor.DBInTransaction
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

import java.time.Instant
import java.time.temporal.ChronoUnit

class RefreshTokenRepositorySpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  private lazy val userRepo  = new UserRepository
  private lazy val tokenRepo = new RefreshTokenRepository

  private def createUserAndToken(
    usedAt: Option[Instant] = None,
    deletedAt: Option[Instant] = None
  ): DBInTransaction[(UserId, RefreshToken)] = {
    val user  = TestData.user()
    val token = TestData.refreshToken(userId = user.id, usedAt = usedAt, deletedAt = deletedAt)
    for {
      _     <- userRepo.create(user)
      saved <- tokenRepo.save(token)
    } yield (user.id, saved)
  }

  "RefreshTokenRepository" should {
    "save and list active tokens" in withRollback {
      for {
        (userId, token) <- createUserAndToken()
        active          <- tokenRepo.listActive(userId)
      } yield {
        active.size shouldBe 1
        active.head.id shouldBe token.id
      }
    }

    "listActive excludes used tokens" in withRollback {
      for {
        (userId, token) <- createUserAndToken()
        _               <- tokenRepo.update(token.id, _.usedAt(Instant.now()))
        active          <- tokenRepo.listActive(userId)
      } yield active shouldBe empty
    }

    "listActive excludes soft-deleted tokens" in withRollback {
      for {
        (userId, token) <- createUserAndToken()
        _               <- tokenRepo.update(token.id, _.deletedAt(Instant.now()))
        active          <- tokenRepo.listActive(userId)
      } yield active shouldBe empty
    }

    "listActive returns empty for unknown user" in withRollback {
      tokenRepo.listActive(TestData.randomUserId()).map(_ shouldBe empty)
    }

    "findForUpdate returns token" in withRollback {
      for {
        (_, token) <- createUserAndToken()
        found      <- tokenRepo.findForUpdate(token.id)
      } yield found.map(_.id) shouldBe Some(token.id)
    }

    "findForUpdate returns None for unknown id" in withRollback {
      tokenRepo.findForUpdate(TestData.randomRefreshTokenId()).map(_ shouldBe None)
    }

    "update marks token as used" in withRollback {
      for {
        (_, token) <- createUserAndToken()
        _          <- tokenRepo.update(token.id, _.usedAt(Instant.now()))
        found      <- tokenRepo.findForUpdate(token.id)
      } yield found.flatMap(_.usedAt) shouldBe defined
    }
  }

  "deleteUsedOrDeletedBefore" should {
    "delete tokens used before cutoff" in withRollback {
      val cutoff = Instant.now()
      val old    = cutoff.minus(1, ChronoUnit.DAYS)
      for {
        (userId, _) <- createUserAndToken(usedAt = Some(old))
        _           <- tokenRepo.deleteUsedOrDeletedBefore(cutoff)
        active      <- tokenRepo.listActive(userId)
      } yield active shouldBe empty
    }

    "delete tokens soft-deleted before cutoff" in withRollback {
      val cutoff = Instant.now()
      val old    = cutoff.minus(1, ChronoUnit.DAYS)
      for {
        (userId, _) <- createUserAndToken(deletedAt = Some(old))
        _           <- tokenRepo.deleteUsedOrDeletedBefore(cutoff)
        active      <- tokenRepo.listActive(userId)
      } yield active shouldBe empty
    }

    "NOT delete active tokens" in withRollback {
      val cutoff = Instant.now()
      for {
        (userId, _) <- createUserAndToken()
        _           <- tokenRepo.deleteUsedOrDeletedBefore(cutoff)
        active      <- tokenRepo.listActive(userId)
      } yield active.size shouldBe 1
    }

    "NOT delete tokens used after cutoff" in withRollback {
      val cutoff = Instant.now()
      val future = cutoff.plus(1, ChronoUnit.DAYS)
      for {
        (userId, token) <- createUserAndToken(usedAt = Some(future))
        _               <- tokenRepo.deleteUsedOrDeletedBefore(cutoff)
        found           <- tokenRepo.findForUpdate(token.id)
      } yield found shouldBe defined
    }
  }
}
