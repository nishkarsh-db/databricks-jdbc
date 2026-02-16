package com.databricks.jdbc.exception;

import java.io.IOException;
import java.util.Map;

public class DatabricksRetryHandlerException extends IOException {
  private final int errCode;
  private final Map<String, String> headers;

  public DatabricksRetryHandlerException(String message, int errCode) {
    super(message);
    this.errCode = errCode;
    this.headers = null;
  }

  public DatabricksRetryHandlerException(String message, int errCode, Map<String, String> headers) {
    super(message);
    this.errCode = errCode;
    this.headers = headers;
  }

  public int getErrCode() {
    return errCode;
  }

  public Map<String, String> getHeaders() {
    return headers;
  }
}
