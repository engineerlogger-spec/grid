# Bank sync setup (Revolut)

Grid reads your Revolut transactions through **Enable Banking**, a licensed Open Banking provider. Because your Google Wallet cards are Revolut cards and PayPal is funded from Revolut, those payments arrive this way too. PayPal purchases show up as the real merchant ("PAYPAL *NETFLIX" becomes Netflix, paid with PayPal).

Setup takes about 10 minutes and happens once. It's free for personal use: Enable Banking's *restricted mode* lets your application read only the accounts you link yourself.

## 1. Create an Enable Banking account
Go to <https://enablebanking.com/sign-in/>, enter your email, and open the sign-in link it sends you.

## 2. Register an application
In the Control Panel, open **API applications** and fill in the form:

| Field | Value |
|---|---|
| Environment | **Production** |
| Private key | the default (generated in your browser) |
| Application name | `Grid` (Revolut shows this name when you approve) |
| Redirect URLs | `https://engineerlogger-spec.github.io/grid/bank-callback/` |

Press **Register**. Your browser downloads the private key as `<application-id>.pem`. Keep this file private: it is the key to reading your account.

## 3. Link your Revolut account
On the application, choose **Activate by linking accounts** and approve access to your Revolut account. This puts the application in restricted mode: only your own accounts can be read, and no contract is needed.

## 4. Connect Grid
1. Copy the `.pem` file to your phone. Bluetooth, a USB cable or a private cloud folder are all fine; don't email it.
2. In Grid, open **Settings → Bank sync → Import key file** and pick the file. The application ID is read from the file name.
3. Choose your country and how much history to import (3 months by default), then tap **Connect Revolut**.
4. Approve in Revolut. You come back to Grid automatically.
   - If not, the page has a **Return to Grid** button.
   - As a last resort, copy the page's address and use **Paste link** in Grid.
5. Pick the accounts to sync and tap **Start sync**.

## What happens next
- Grid syncs three times a day. **Sync now** fetches immediately.
- Revolut allows about four fetches a day, so if you tap it a lot it may say to try later.
- **Card payments and direct debits** are added automatically. Payments Grid already caught from a notification are merged, and the bank's final amount wins.
- **Merchants Grid hasn't seen yet** wait in **Detected → From Revolut**, grouped by merchant. One tap categorises the whole group, and future payments to that merchant are categorised automatically.
- **Transfers and money coming in** wait for your OK once per person. Rent to your landlord, for example, is automatic after the first time.
  - If rent is already a bill in Grid, it's matched to that bill and needs no tap at all.
- **Moves between your own pockets, vaults and currencies** are ignored.
- **Every 180 days**, Open Banking requires you to approve access again. Grid reminds you 7 days and 1 day before. When access has expired, Home shows a **Reconnect** card.

## Privacy
- The key is stored encrypted on your phone (Android Keystore) and is never included in backups. A restored phone needs to be connected again.
- Grid talks to Enable Banking directly from your phone. There's no Grid server.

## Helping improve detection
Revolut's exact wording for transfers, vaults and so on can vary. If something is filed wrongly, use **Settings → Bank sync → Share raw data** and send the text. It contains the last 20 transactions exactly as the bank sent them, so check it before sharing.
