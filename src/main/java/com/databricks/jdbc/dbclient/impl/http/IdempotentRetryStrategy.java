package com.databricks.jdbc.dbclient.impl.http;

import com.databricks.jdbc.api.internal.IDatabricksConnectionContext;
import com.databricks.jdbc.log.JdbcLogger;
import com.databricks.jdbc.log.JdbcLoggerFactory;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.apache.http.HttpStatus;

/**
 * Retry strategy for idempotent requests - retries all codes except specific client errors. Follow
 * Retry-After header, if it is not present then use exponential backoff.
 */
public class IdempotentRetryStrategy implements IRetryStrategy {

  private static final JdbcLogger LOGGER =
      JdbcLoggerFactory.getLogger(IdempotentRetryStrategy.class);

  private static final Set<Class<? extends RuntimeException>> NON_RETRIABLE_EXCEPTIONS =
      new HashSet<>(
          Arrays.asList(
              IllegalArgumentException.class,
              IllegalStateException.class,
              UnsupportedOperationException.class,
              IndexOutOfBoundsException.class,
              NullPointerException.class,
              ClassCastException.class,
              NumberFormatException.class,
              ArrayIndexOutOfBoundsException.class,
              ArrayStoreException.class,
              ArithmeticException.class,
              NegativeArraySizeException.class));

  private static final Set<Integer> NON_RETRIABLE_HTTP_CODES =
      new HashSet<>(Arrays.asList(400, 401, 403, 404, 405, 409, 410, 411, 412, 413, 414, 415, 416));

  /**
   * Idempotent retry strategy implementation - retries aggressively for safe operations.
   *
   * <p><b>Retriable Status Codes:</b>
   *
   * <ul>
   *   <li>All 5xx server errors (500, 502, 503, etc.)
   *   <li>429 Too Many Requests (if rate limit retry enabled)
   *   <li>All error codes EXCEPT: 400, 401, 403, 404, 405, 409, 410, 411, 412, 413, 414, 415, 416
   *   <li>Custom API retriable codes (from connection context)
   * </ul>
   *
   * <p><b>Retry Delay Calculation:</b> Uses Retry-After header if present, otherwise exponential
   * backoff (1s to 10s with jitter).
   */
  @Override
  public Optional<Integer> shouldRetryAfter(
      int statusCode,
      Optional<Integer> retryAfterHeader,
      int executionAttempt,
      IDatabricksConnectionContext connectionContext,
      RetryTimeoutManager retryTimeoutManager) {

    LOGGER.debug(
        "Received HTTP response. Status code: {}, Retry-After header: {}, attempt: {}",
        statusCode,
        retryAfterHeader.isPresent() ? retryAfterHeader.get() + "ms" : "not present",
        executionAttempt);

    // Check if this is an API retriable code
    boolean isApiRetriableCode = connectionContext.getApiRetriableHttpCodes().contains(statusCode);

    // If not retriable by standard logic AND not an API retriable code, don't retry
    if (!isStatusCodeRetriable(statusCode, connectionContext) && !isApiRetriableCode) {
      return Optional.empty();
    }

    int retryAfter =
        retryAfterHeader.orElseGet(() -> RetryUtils.calculateExponentialBackoff(executionAttempt));

    // Let timeout manager handle the timeout with the isApiRetriableCode flag
    if (!retryTimeoutManager.evaluateRetryTimeoutForResponse(
        statusCode, retryAfter, isApiRetriableCode)) {
      LOGGER.error("Retry timeout reached after attempt {}, returning response.", executionAttempt);
      return Optional.empty();
    }

    return Optional.of(retryAfter);
  }

  /**
   * Idempotent exception retry strategy - retries most exceptions except programming errors.
   *
   * <p><b>Retriable Exceptions:</b> All exceptions EXCEPT specific runtime exceptions:
   * IllegalArgumentException, IllegalStateException, UnsupportedOperationException,
   * IndexOutOfBoundsException, NullPointerException, ClassCastException, NumberFormatException,
   * ArrayIndexOutOfBoundsException, ArrayStoreException, ArithmeticException,
   * NegativeArraySizeException.
   *
   * <p><b>Retry Delay:</b> Exponential backoff (1s to 10s with jitter).
   */
  @Override
  public Optional<Integer> shouldRetryAfter(
      Exception e, int executionAttempt, RetryTimeoutManager retryTimeoutManager) {
    LOGGER.debug(
        "Received exception. Exception type: {}, attempt: {}",
        e.getClass().getSimpleName(),
        executionAttempt);

    if (!isExceptionRetrieable(e)) {
      LOGGER.debug(
          "Exception {} is not retriable for idempotent request", e.getClass().getSimpleName());
      return Optional.empty();
    }

    int retryAfter = RetryUtils.calculateExponentialBackoff(executionAttempt);
    if (!retryTimeoutManager.evaluateRetryTimeoutForException(retryAfter)) {
      LOGGER.error(
          "Retry timeout reached for exception. Exception: {}, retry after: {} milliseconds",
          e.getClass().getSimpleName(),
          retryAfter);
      return Optional.empty();
    }

    return Optional.of(retryAfter);
  }

  private boolean isStatusCodeRetriable(
      int statusCode, IDatabricksConnectionContext connectionContext) {
    if (statusCode >= 200 && statusCode < 300) {
      return false;
    }

    boolean isRetriable;
    switch (statusCode) {
      case HttpStatus.SC_SERVICE_UNAVAILABLE:
        isRetriable = connectionContext.shouldRetryTemporarilyUnavailableError();
        break;
      case HttpStatus.SC_TOO_MANY_REQUESTS:
        isRetriable = connectionContext.shouldRetryRateLimitError();
        break;
      default:
        isRetriable = !NON_RETRIABLE_HTTP_CODES.contains(statusCode);
        break;
    }

    if (!isRetriable) {
      LOGGER.error("Status code {} is not retriable for idempotent request", statusCode);
    }
    return isRetriable;
  }

  private boolean isExceptionRetrieable(Exception e) {
    return !NON_RETRIABLE_EXCEPTIONS.contains(e.getClass());
  }
}
