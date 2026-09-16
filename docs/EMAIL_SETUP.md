# Email sign-in delivery (§2, §3, §44)

Email is the second way into Good Post. It is **sign-in only** — an address opens
an existing account and can never create one, because §19 anchors the abuse
identity on the mobile number.

Delivery has two routes, selected by `EMAIL_DELIVERY_MODE`. Both are behind the
`Mailer` interface, so switching is a variable, not a code change.

| Mode | Sender | Reaches | Needs |
|---|---|---|---|
| `smtp` | an authenticated mailbox (Gmail) | **any** recipient | 2-Step Verification + an app password |
| `resend` | a provider API | any recipient | a **domain verified in Resend** |
| `console` | the process log | nobody | refused in production — it writes live codes to the log |
| `disabled` (default) | — | nobody | nothing; the endpoint answers `email_unavailable` |

## Why `smtp` exists

Resend will not send to any address except its own account owner's until a
domain is verified. Its own words, from a real refusal:

```
403 validation_error
You can only send testing emails to your own email address (owner@example.com).
To send emails to other recipients, please verify a domain at resend.com/domains
```

That is a hard limit on unverified accounts, not a bug in this code: the backend
reported it honestly as `503 email_unavailable`, which the app words as "email
sign-in is unavailable". Every provider requires a domain for real deliverability
— the alternative is one provider or another's shared sandbox sender, which
Gmail treats accordingly and files away where nobody looks.

`smtp` sidesteps that by not needing a domain at all: the sender is a real
mailbox that Google authenticates and signs for, so a code reaches any
recipient. The trade is that the account's own reputation and daily limit are
now the app's.

## Setting up the Gmail route

1. **Turn on 2-Step Verification** for the Google account:
   https://myaccount.google.com/security — Google does not offer app passwords
   without it.
2. **Create an app password**: https://myaccount.google.com/apppasswords →
   name it something recognisable (`ClearView backend`) → copy the **16-character**
   value. This is not the account password, and it is shown only once.
3. **Set these on Render** (Environment → add / edit):

| Variable | Value |
|---|---|
| `EMAIL_DELIVERY_MODE` | `smtp` |
| `SMTP_USER` | the full Gmail address, e.g. `studymuddassir@gmail.com` |
| `SMTP_PASS` | the 16-character app password |
| `EMAIL_FROM` | `ClearView <studymuddassir@gmail.com>` |

   `SMTP_HOST` and `SMTP_PORT` default to `smtp.gmail.com` and `465` (implicit
   TLS). Port 587 works too — `secure` is derived from the port rather than a
   second variable that can contradict it.

   **`EMAIL_FROM` must be the authenticated mailbox**, or an alias that account
   is authorised to send as. A From: the provider has not authorised is a
   reliable way to have the message rejected outright.

   Set all four in one go. A chosen-but-unconfigured mode is **refused at boot**
   in production, so setting the mode before the password would stop the service
   rather than quietly degrade — a loud failure at deploy time beats a silent
   `email_unavailable` at sign-in.

4. **Revoke it when you stop using it**: https://myaccount.google.com/apppasswords
   → remove `ClearView backend`. The password is a credential for the whole
   account's outbound mail.

## What this route costs

From Google's own limits page (https://support.google.com/mail/answer/22839):

- **500 recipients or 500 emails per day**, per account.
- Exceeding it blocks sending for **1 to 24 hours**.
- A high bounce rate can also block sending for up to 24 hours.

So this is right for development, for a handful of testers, and for the period
before a domain exists. It is not right for a launched app with real sign-up
volume — verify a domain in Resend and switch `EMAIL_DELIVERY_MODE` to `resend`
before that point. Nothing else has to change.

## Rules this code keeps, on either route

- The code is **HMAC'd at rest** and compared in constant time; there is no
  plaintext column.
- The send is attempted **before** answering, and a delivery failure **deletes
  the challenge** rather than leaving a code nobody will receive to burn the
  user's hourly allowance.
- Requesting a code answers **identically whether or not the address has an
  account** — `email_not_registered` is revealed only after the code is proved.
- The provider's error **message** is never logged, because it can quote the
  recipient address and the content it rejected (§38). Only its short error
  **name** is, which is what tells a real refusal apart from an outage.
- `EMAIL_DELIVERY_MODE=console` is refused in production: it prints live codes.

## Verifying it end to end

Once the four variables are set, the check that matters is a real send to a real
inbox — not a mocked transport:

1. `POST /api/v1/auth/email/otp` with an address **that belongs to an existing
   account**. A fresh address is a valid test of delivery but will answer
   `email_not_registered` after the code, by design (§19).
2. Watch the service log: a refusal logs
   `[email] provider rejected the send: HTTP <status> (<provider error name>)`.
   Silence means the provider accepted it.
3. The code arrives with subject **"Your ClearView sign-in code"**. A 10-minute
   TTL applies (`EMAIL_OTP_TTL_MINUTES`), and the code step offers *Send a new
   code* if it expires.
