# ZeroBar Privacy Policy

_Last updated: 17 November 2026_

This policy explains what ZeroBar ("the app", "we") does with information when you use it. The app is a
push-to-talk voice and messaging tool that works over the internet and directly between nearby phones.

## What stays on your phone

- The name you choose, and the encryption keys the app creates for you.
- The groups and one-to-one chats you are in, and the names of people you have seen on the app.
- A short list of recent activity (who talked or wrote in which chat, and when). It contains no message content.
- Your settings.

Voice, text messages, pictures and locations are kept only in the phone's memory, so that they can be read or
played again, and are deleted automatically after the time you choose (at most 10 minutes). They are never written to
the phone's storage or kept on any server. Photos taken with the in-app camera
button are deleted from the phone as soon as they are sent.

## What leaves your phone

- **Messages, voice, pictures and locations** go only to the people in the chat you send them to. They are
  encrypted on your phone (AES-256) and can only be read by the members of that chat. They travel either through an
  internet relay server or directly to nearby phones over Bluetooth or Wi-Fi.
- **Your directory entry** (your chosen name and public key) is published on the relay server so that other users
  of the app can find you and start a chat. Other users can see it.
- **Group invitations** and **group names** are kept on the relay server, encrypted, until they are no longer needed,
  so people who are offline receive them later.
- **The relay server** is a third-party public MQTT service (by default broker.emqx.io, with broker.hivemq.com as a
  backup). Its operator cannot read your messages, but like any internet service it can see your IP address, when
  you are online and how much data you send. You can choose a different server in Settings.
- **Emergency (SOS) messages** are broadcast over Bluetooth to nearby phones without a connection. They contain an
  app identifier, the chosen emergency message and your position. They are not encrypted, so that anyone who can
  help is able to read them. You choose in Settings whether only your contacts' phones or all phones show them.

## Permissions

- **Microphone:** used only while you hold the talk button (or after you tap it from the widget or a notification).
- **Location:** used when you share your location, send a photo with its location, or send an SOS. Android also
  requires this permission for Bluetooth scanning; for that purpose your position is not read.
- **Bluetooth, nearby devices and Wi-Fi:** to reach phones near you when there is no internet.
- **Notifications:** to tell you when someone talks, writes, invites you or sends an emergency message.
- **Run in background / ignore battery optimisation:** so incoming calls reach you with the app closed, like a radio.
- **Camera:** the app opens your phone's camera app; it does not access the camera itself.

## What we do not do

- No advertising, no analytics, no tracking, and no selling or sharing of data with advertisers.
- We do not run servers that store your messages.

## Optional sign-in

If e-mail sign-in is enabled in a future version, your e-mail address, name, mobile number and your choice about
receiving news would be stored with our sign-in provider. Marketing messages would only be sent if you opted in, and
you could ask for this data to be deleted at any time. Sign-in is not enabled in the current version.

## Deleting your data

Delete a chat or leave a group from inside the app, or uninstall the app to remove everything stored on your phone.
Your directory entry on the relay server expires when you stop using the app; you can also ask us to remove it.

## Children

The app is not directed at children under 13.

## Contact

Questions or requests: **ugoyel@gmail.com**
