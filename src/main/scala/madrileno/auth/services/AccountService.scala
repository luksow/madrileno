package madrileno.auth.services

import cats.effect.{Clock, IO}
import madrileno.auth.repositories.{RefreshTokenRepository, UserAuthRepository}
import madrileno.user.domain.{UserAccountDeleted, UserId}
import madrileno.user.repositories.UserRepository
import madrileno.utils.db.transactor.Transactor
import madrileno.utils.events.outbox.Outbox
import madrileno.utils.observability.{LoggingSupport, TelemetryContext}

enum DeleteAccountResult {
  case Deleted, AlreadyDeleted
}

class AccountService(
  userRepository: UserRepository,
  userAuthRepository: UserAuthRepository,
  refreshTokenRepository: RefreshTokenRepository,
  outbox: Outbox,
  transactor: Transactor
)(using
  TelemetryContext,
  Clock[IO])
    extends LoggingSupport {

  def deleteAccount(userId: UserId): IO[DeleteAccountResult] =
    transactor
      .inTransaction {
        Clock[IO].realTimeInstant.flatMap { now =>
          userRepository.anonymize(userId, now).flatMap {
            case false => IO.pure(DeleteAccountResult.AlreadyDeleted)
            case true  =>
              userAuthRepository.softDeleteByUser(userId, now) *>
                refreshTokenRepository.revokeAllForUser(userId, now) *>
                outbox.publishTransactionally(UserAccountDeleted(userId)).as(DeleteAccountResult.Deleted)
          }
        }
      }
      .flatTap {
        case DeleteAccountResult.Deleted        => logger.info(s"account deleted: $userId")
        case DeleteAccountResult.AlreadyDeleted => IO.unit
      }
}
