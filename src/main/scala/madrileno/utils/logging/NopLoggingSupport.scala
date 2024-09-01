package madrileno.utils.logging

trait NopLoggingSupport { this: LoggingSupport =>
  override lazy val logger: org.slf4j.Logger = org.slf4j.helpers.NOPLogger.NOP_LOGGER
}
