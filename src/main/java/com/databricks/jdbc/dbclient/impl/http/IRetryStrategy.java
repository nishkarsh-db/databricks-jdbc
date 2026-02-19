package com.databricks.jdbc.dbclient.impl.http;

import com.databricks.jdbc.api.internal.IDatabricksConnectionContext;
import java.util.Optional;

/**
 * Strategy interface for determining retry behavior for HTTP requests. Implementations define
 * different retry policies (idempotent vs non-idempotent) with varying levels of retry
 * aggressiveness.
 */
public interface IRetryStrategy {

  /**
   * Determines if an HTTP response should be retried and calculates the retry delay.
   *
   * <p>This method evaluates whether a failed HTTP request should be retried based on the status
   * code and available retry guidance (Retry-After header). The decision process:
   *
   * <ul>
   *   <li>Checks if status code is retriable per the strategy's policy
   *   <li>Checks if status code is in custom API retriable codes (ApiRetriableHttpCodes)
   *   <li>Calculates retry delay from Retry-After header or exponential backoff
   *   <li>Validates retry delay against timeout budgets via RetryTimeoutManager
   * </ul>
   *
   * <p><b>Timeout Management:</b> The RetryTimeoutManager maintains separate timeout budgets for
   * different error types (503, 429, API codes, other errors). For each retry attempt:
   *
   * <ol>
   *   <li>The retry delay is subtracted from the appropriate timeout budget
   *   <li>If the remaining budget is positive, retry is allowed
   *   <li>If the budget is exhausted (≤0), retry is denied and the request fails
   * </ol>
   *
   * <p>Initial timeout values are configured via connection context (e.g.,
   * TemporarilyUnavailableRetryTimeout for 503, RateLimitRetryTimeout for 429, ApiRetryTimeout for
   * custom codes).
   *
   * @param statusCode the HTTP status code from the response
   * @param retryAfterHeader optional Retry-After header value in milliseconds
   * @param executionAttempt the current execution attempt number (0-based)
   * @param connectionContext the connection context with retry configuration
   * @param retryTimeoutManager manages timeout tracking across retries
   * @return Optional containing retry delay in milliseconds if retry should occur, empty otherwise
   */
  Optional<Integer> shouldRetryAfter(
      int statusCode,
      Optional<Integer> retryAfterHeader,
      int executionAttempt,
      IDatabricksConnectionContext connectionContext,
      RetryTimeoutManager retryTimeoutManager);

  /**
   * Determines if a request should be retried after an exception and calculates the retry delay.
   *
   * <p>This method evaluates whether a failed HTTP request that threw an exception should be
   * retried. The decision process:
   *
   * <ul>
   *   <li>Checks if the exception type is retriable per the strategy's policy
   *   <li>Calculates retry delay using exponential backoff
   *   <li>Validates retry delay against exception timeout budget via RetryTimeoutManager
   * </ul>
   *
   * <p><b>Timeout Management:</b> The RetryTimeoutManager maintains a separate timeout budget for
   * exception-based retries. For each retry attempt:
   *
   * <ol>
   *   <li>The retry delay (exponential backoff) is subtracted from the exception timeout budget
   *   <li>If the remaining budget is positive, retry is allowed
   *   <li>If the budget is exhausted (≤0), retry is denied and the exception is thrown
   * </ol>
   *
   * <p>The initial exception timeout value is configured via connection context.
   *
   * @param e the exception that occurred during request execution
   * @param executionAttempt the current execution attempt number (0-based)
   * @param retryTimeoutManager manages timeout tracking across retries
   * @return Optional containing retry delay in milliseconds if retry should occur, empty otherwise
   */
  Optional<Integer> shouldRetryAfter(
      Exception e, int executionAttempt, RetryTimeoutManager retryTimeoutManager);
}
