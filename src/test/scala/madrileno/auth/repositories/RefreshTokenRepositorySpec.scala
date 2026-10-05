package madrileno.auth.repositories

import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{Deferred, IO}
import madrileno.auth.domain.*
import madrileno.support.{TestData, TestTransactor}
import madrileno.user.domain.UserId
import madrileno.user.repositories.UserRepository
import madrileno.utils.db.transactor.DBInTransaction
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.concurrent.duration.*

class RefreshTokenRepositorySpec extends AsyncWordSpec with AsyncIOSpec with Matchers with TestTransactor {

  private lazy val userRepo  = new UserRepository
  private lazy val tokenRepo = new RefreshTokenRepository

  private def createUserAndToken(usedAt: Option[Instant] = None, deletedAt: Option[Instant] = None): DBInTransaction[(UserId, RefreshToken)] = {
    val user  = TestData.user()
    val token = TestData.refreshToken(userId = user.id, usedAt = usedAt, deletedAt = deletedAt)
    for {
      _     <- userRepo.create(user, Instant.now())
      saved <- tokenRepo.save(token)
    } yield (user.id, saved)
  }

  "RefreshTokenRepository" should {
    "save and list active tokens" in withRollback {
      for {
        (userId, token) <- createUserAndToken()
        active          <- tokenRepo.listActive(userId, Instant.now())
      } yield {
        active.size shouldBe 1
        active.head.id shouldBe token.id
      }
    }

    "listActive excludes used tokens" in withRollback {
      for {
        (userId, token) <- createUserAndToken()
        _               <- tokenRepo.update(token.id, _.usedAt(Instant.now()))
        active          <- tokenRepo.listActive(userId, Instant.now())
      } yield active shouldBe empty
    }

    "listActive excludes soft-deleted tokens" in withRollback {
      for {
        (userId, token) <- createUserAndToken()
        _               <- tokenRepo.update(token.id, _.deletedAt(Instant.now()))
        active          <- tokenRepo.listActive(userId, Instant.now())
      } yield active shouldBe empty
    }

    "listActive returns empty for unknown user" in withRollback {
      tokenRepo.listActive(TestData.randomUserId(), Instant.now()).map(_ shouldBe empty)
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

    "findForUpdateBySecretHash returns the token owning that hash" in withRollback {
      for {
        (_, token) <- createUserAndToken()
        found      <- tokenRepo.findForUpdateBySecretHash(token.secretHash)
      } yield found.map(_.id) shouldBe Some(token.id)
    }

    "findForUpdateBySecretHash returns None for an unknown hash" in withRollback {
      tokenRepo.findForUpdateBySecretHash(TestData.refreshTokenSecret().hash).map(_ shouldBe None)
    }

    "findAndLockFamilyBySecretHash returns the token and serializes a replay's revocation behind an in-flight rotation" in {
      val now      = Instant.now()
      val user     = TestData.user()
      val familyId = TestData.randomRefreshTokenFamilyId()
      val replayed = TestData.issuedRefreshToken(userId = user.id, familyId = familyId, usedAt = Some(now.minusSeconds(3600)))
      val live     = TestData.issuedRefreshToken(userId = user.id, familyId = familyId)
      val next     = TestData.refreshToken(userId = user.id, familyId = familyId)
      for {
        _                 <- transactor.inTransaction(userRepo.create(user, now) *> tokenRepo.save(replayed.token) *> tokenRepo.save(live.token))
        rotationHoldsLock <- Deferred[IO, Unit]
        releaseRotation   <- Deferred[IO, Unit]
        rotation          <- transactor.inTransaction {
                      tokenRepo.findAndLockFamilyBySecretHash(live.token.secretHash).flatMap { locked =>
                        tokenRepo.update(live.token.id, _.usedAt(now)) *>
                          rotationHoldsLock.complete(()) *>
                          releaseRotation.get *>
                          tokenRepo.save(next).as(locked.map(_.id))
                      }
                    }.start
        _      <- rotationHoldsLock.get
        replay <- transactor.inTransaction {
                    tokenRepo.findAndLockFamilyBySecretHash(replayed.token.secretHash).flatMap { _ =>
                      tokenRepo.revokeFamily(familyId, now)
                    }
                  }.start
        _         <- IO.sleep(300.millis)
        _         <- releaseRotation.complete(())
        lockedId  <- rotation.joinWithNever
        _         <- replay.joinWithNever
        activeNow <- transactor.inSession(tokenRepo.listActive(user.id, now))
        nextAfter <- transactor.inSession(tokenRepo.findBySecretHash(next.secretHash))
      } yield {
        lockedId shouldBe Some(live.token.id)
        activeNow shouldBe empty
        nextAfter.flatMap(_.deletedAt) shouldBe defined
      }
    }

    "revokeFamily invalidates every token in the family and nothing else" in withRollback {
      val now      = Instant.now()
      val user     = TestData.user()
      val familyId = TestData.randomRefreshTokenFamilyId()
      val used     = TestData.refreshToken(userId = user.id, familyId = familyId, usedAt = Some(now))
      val live     = TestData.refreshToken(userId = user.id, familyId = familyId)
      val other    = TestData.refreshToken(userId = user.id)
      for {
        _         <- userRepo.create(user, now)
        _         <- tokenRepo.save(used)
        _         <- tokenRepo.save(live)
        _         <- tokenRepo.save(other)
        _         <- tokenRepo.revokeFamily(familyId, now)
        active    <- tokenRepo.listActive(user.id, now)
        liveAfter <- tokenRepo.findForUpdate(live.id)
        usedAfter <- tokenRepo.findForUpdate(used.id)
      } yield {
        active.map(_.id) shouldBe List(other.id)
        liveAfter.flatMap(_.deletedAt) shouldBe defined
        usedAfter.flatMap(_.deletedAt) shouldBe defined
      }
    }

    "update marks token as used" in withRollback {
      for {
        (_, token) <- createUserAndToken()
        _          <- tokenRepo.update(token.id, _.usedAt(Instant.now()))
        found      <- tokenRepo.findForUpdate(token.id)
      } yield found.flatMap(_.usedAt) shouldBe defined
    }

    "revokeAllForUser invalidates active refresh tokens" in withRollback {
      val now = Instant.now()
      for {
        (userId, _) <- createUserAndToken()
        before      <- tokenRepo.listActive(userId, now)
        _ = before should not be empty
        _     <- tokenRepo.revokeAllForUser(userId, now)
        after <- tokenRepo.listActive(userId, now)
      } yield after shouldBe empty
    }
  }

  "deleteStaleBefore" should {
    "keep used tokens as replay evidence until their expiry has passed the cutoff" in withRollback {
      val cutoff = Instant.now()
      val old    = cutoff.minus(1, ChronoUnit.DAYS)
      for {
        (_, token) <- createUserAndToken(usedAt = Some(old))
        _          <- tokenRepo.deleteStaleBefore(cutoff)
        found      <- tokenRepo.findForUpdate(token.id)
      } yield found shouldBe defined
    }

    "delete used tokens once their expiry has passed the cutoff" in withRollback {
      val cutoff = Instant.now()
      val old    = cutoff.minus(1, ChronoUnit.DAYS)
      val user   = TestData.user()
      val token  = TestData.refreshToken(userId = user.id, usedAt = Some(old.minusSeconds(60)), expiresAt = old)
      for {
        _     <- userRepo.create(user, Instant.now())
        _     <- tokenRepo.save(token)
        _     <- tokenRepo.deleteStaleBefore(cutoff)
        found <- tokenRepo.findForUpdate(token.id)
      } yield found shouldBe None
    }

    "keep revoked tokens until their expiry has passed the cutoff" in withRollback {
      val cutoff = Instant.now()
      val old    = cutoff.minus(1, ChronoUnit.DAYS)
      for {
        (_, token) <- createUserAndToken(deletedAt = Some(old))
        _          <- tokenRepo.deleteStaleBefore(cutoff)
        found      <- tokenRepo.findForUpdate(token.id)
      } yield found shouldBe defined
    }

    "NOT delete active tokens" in withRollback {
      val cutoff = Instant.now()
      for {
        (userId, _) <- createUserAndToken()
        _           <- tokenRepo.deleteStaleBefore(cutoff)
        active      <- tokenRepo.listActive(userId, Instant.now())
      } yield active.size shouldBe 1
    }

    "NOT delete tokens used after cutoff" in withRollback {
      val cutoff = Instant.now()
      val future = cutoff.plus(1, ChronoUnit.DAYS)
      for {
        (_, token) <- createUserAndToken(usedAt = Some(future))
        _          <- tokenRepo.deleteStaleBefore(cutoff)
        found      <- tokenRepo.findForUpdate(token.id)
      } yield found shouldBe defined
    }

    "delete tokens expired before cutoff" in withRollback {
      val cutoff       = Instant.now()
      val oldExpiresAt = cutoff.minus(1, ChronoUnit.DAYS)
      val user         = TestData.user()
      val token        = TestData.refreshToken(userId = user.id, expiresAt = oldExpiresAt)
      for {
        _     <- userRepo.create(user, Instant.now())
        _     <- tokenRepo.save(token)
        _     <- tokenRepo.deleteStaleBefore(cutoff)
        found <- tokenRepo.findForUpdate(token.id)
      } yield found shouldBe None
    }

    "NOT delete tokens that expire after cutoff" in withRollback {
      val cutoff          = Instant.now()
      val futureExpiresAt = cutoff.plus(1, ChronoUnit.DAYS)
      val user            = TestData.user()
      val token           = TestData.refreshToken(userId = user.id, expiresAt = futureExpiresAt)
      for {
        _     <- userRepo.create(user, Instant.now())
        _     <- tokenRepo.save(token)
        _     <- tokenRepo.deleteStaleBefore(cutoff)
        found <- tokenRepo.findForUpdate(token.id)
      } yield found shouldBe defined
    }
  }
}
