/**
 * A failure with a deliberate HTTP shape.
 *
 * Routes throw these and `buildApp`'s central error handler turns them into
 * `{ error: <type> }` with the given status.
 *
 * The code is a stable machine-readable string rather than a sentence because
 * the Android client branches on it: `channel_not_found` has to word one
 * message, `media_unavailable` another, and `invalid_credentials` a third.
 * Prose would make the client parse English — and the vocabulary is small on
 * purpose, because a broadcast product with one credential has few ways to go
 * wrong.
 *
 * `message` is a developer aid only — the error handler includes it in the
 * response when not in production, and for 5xx it is replaced by
 * `internal_error` so a server fault never describes its own internals.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly type: string;

  constructor(status: number, type: string, message?: string) {
    super(message ?? type);
    this.name = 'ApiError';
    this.status = status;
    this.type = type;
  }
}

export const badRequest = (type: string, message?: string): ApiError =>
  new ApiError(400, type, message);

export const unauthorized = (type = 'unauthorized', message?: string): ApiError =>
  new ApiError(401, type, message);

export const forbidden = (type: string, message?: string): ApiError =>
  new ApiError(403, type, message);

export const notFound = (type: string, message?: string): ApiError =>
  new ApiError(404, type, message);

export const conflict = (type: string, message?: string): ApiError =>
  new ApiError(409, type, message);

export const tooManyRequests = (type: string, message?: string): ApiError =>
  new ApiError(429, type, message);

/**
 * 503 rather than 500: the request was well-formed and the caller may simply
 * retry, which is a materially different instruction from "the server broke".
 */
export const serviceUnavailable = (type: string, message?: string): ApiError =>
  new ApiError(503, type, message);
