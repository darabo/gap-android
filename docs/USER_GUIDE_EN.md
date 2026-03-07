# Gap Mesh User Guide

Welcome to Gap Mesh! This guide will help you get started with the app, even if you're not familiar with technology.

---

## What is Gap Mesh?

Gap Mesh is a **messaging app that works without the internet**. It connects your phone directly to nearby phones using Bluetooth, creating a "mesh network" – like a chain of people passing messages to each other.

### Why Use Gap Mesh?

- ✅ **No internet needed** – Chat when there's no Wi-Fi or mobile data
- ✅ **No phone number required** – Stay anonymous
- ✅ **No accounts** – Just install and start chatting
- ✅ **Private & secure** – Your private messages are encrypted
- ✅ **Works anywhere** – Protests, remote areas, emergencies, or just with friends nearby

---

## Getting Started

### Step 1: Install the App

Download and install directly from the [GitHub Releases page (Google Play Store soon. iOS Version on App Store)](https://apps.apple.com/us/app/gap-mesh/id6757211522)

![qr code](https://raw.githubusercontent.com/darabo/gap-android/refs/heads/main/IMG_6338.jpeg)

### Step 2: Grant Permissions

When you first open the app, it will ask for some permissions. Here's why each one is needed:

| Permission        | Why It's Needed                                                                   |
| ----------------- | --------------------------------------------------------------------------------- |
| **Bluetooth**     | To discover and connect with nearby Gap Mesh users                                |
| **Location**      | Required by Android to scan for Bluetooth devices (we don't track your location!) |
| **Notifications** | To alert you when you receive new messages                                        |

> 💡 **Privacy Note**: Gap Mesh does NOT track or store your location. Location permission is only required by Android for Bluetooth scanning.

### Step 3: Choose Your Nickname

Pick a nickname that others will see when you chat. You can change it anytime!

### Step 4: Disable Battery Optimization (Recommended)

Android may stop Gap Mesh from running in the background to save battery. This can cause you to miss messages.

When prompted, tap **"Disable Battery Optimization"** to ensure reliable messaging.

---

## Two Ways to Chat

Gap Mesh offers two types of chat:

### 🔵 Mesh Chat (Offline Mode)

- Works **without internet**
- Uses **Bluetooth** to connect with nearby devices
- Messages hop from phone to phone (up to 7 hops)
- Best for: Local groups, protests, emergencies, remote areas

**How to use**: Just open the app and start chatting! You'll automatically connect with anyone else running Gap Mesh within Bluetooth range.

### 🟢 Location Channels (Online Mode)

- Requires **internet connection**
- Chat with people in your **geographic area**
- Channels are based on your location (block, neighborhood, city, province, region)
- Best for: Finding people in your area, local community discussions

**How to use**: Tap the location icon to see channels near you. Only a rough location is shared – never your exact GPS coordinates.

---

## Sending Messages

### Public Messages

Just type your message and tap **Send**. Everyone in the current channel will see it.

### Private Messages

To send a private message:

1. **Long press** on someone's name in the chat
2. Tap **"Message [name]"**
3. Type your private message

Private messages are **encrypted** – only you and the recipient can read them.

### Sending Images, Voice Notes & Files

Tap the attachment icon next to the message box to:

- 📷 **Send an image** from your gallery
- 🎤 **Record a voice message** (hold to record)
- 📎 **Send a file**

---

## Finding People

### Who's Nearby?

Tap the **People** icon to see:

- Users connected via Bluetooth (mesh)
- Users in your location channel (online)

### Signal Strength Indicators

- 🟢 **Green**: Strong connection
- 🟡 **Yellow**: Medium connection
- 🔴 **Red**: Weak connection

---

## Using Commands

Gap Mesh supports IRC-style commands. Type these in the message box:

| Command            | What It Does                 |
| ------------------ | ---------------------------- |
| `/j #channel`      | Join or create a channel     |
| `/m @name message` | Send a private message       |
| `/w`               | List online users            |
| `/channels`        | Show all discovered channels |
| `/block @name`     | Block someone                |
| `/unblock @name`   | Unblock someone              |
| `/clear`           | Clear chat messages          |

---

## Settings & Customization

Tap the **⚙️ Settings** icon (or the app logo) to access:

### Appearance

- **Light Mode**: White background
- **Dark Mode**: Dark background (easier on the eyes)
- **System**: Follows your phone's theme

### Alternative App Icons

- **Disguise your app**: Change the home screen icon to look like a Calculator, Weather, Notes, Clock, Music, or Flashlight app for added privacy. You can find this in the Settings tab.

### Privacy Options

- **Tor Network** (Optional): Route internet traffic through Tor for extra privacy
- **Proof of Work**: Adds spam protection to location channels

### Run in Background

Enable this to keep receiving messages even when the app is closed.

---

## Safety Features

### 🚨 Emergency Data Wipe

If you need to quickly delete all your data (messages, contacts, settings):

**Triple-tap the app title** (Gap Mesh text at the top)

This instantly erases everything using a crash-resilient process backed by Android's **StrongBox Hardware Security**. Use this in emergencies when you need to protect your privacy. Even if the app forcefully closes, the data remains inaccessible.

### What Data is Stored?

- Your messages (locally on your device only)
- Your nickname
- Your encryption keys
- App settings

### What We Never Collect

- ❌ Your real name
- ❌ Your phone number
- ❌ Your exact location
- ❌ Your messages on any server

---

## Troubleshooting

### "I can't see anyone nearby"

1. Make sure **Bluetooth is turned on**
2. Make sure **Location services are enabled**
3. Check that others near you also have Gap Mesh open
4. Try moving closer to others (Bluetooth range is about 10-30 meters)

### "My messages aren't sending"

1. Check if you see any connected peers (look for the people icon)
2. If using location channels, check your internet connection
3. Try restarting the app

### "The app keeps stopping in the background"

1. Go to **Settings > Apps > Gap Mesh > Battery**
2. Select **"Unrestricted"** or **"Don't optimize"**
3. Disable any battery saver modes

### "I can't join a channel"

Some channels may be password-protected. Ask the channel owner for the password.

---

## Tips for Best Experience

1. **Keep Bluetooth on** for mesh networking
2. **Disable battery optimization** to receive messages reliably
3. **Stay within range** – Bluetooth works best within 30 meters
4. **More people = better network** – Each phone extends the mesh range!

---

## Privacy Summary

| Data                     | Collected?                 |
| ------------------------ | -------------------------- |
| Name/Email               | ❌ No                      |
| Phone Number             | ❌ No                      |
| Exact Location           | ❌ No                      |
| Messages (on servers)    | ❌ No                      |
| Rough Location (geohash) | Only for location channels |

Gap Mesh is **open source** – anyone can verify our privacy claims.

---

## Need More Help?

- 📖 **Technical Documentation**: See our GitHub repository
- 🐛 **Report Bugs**: Create an issue on GitHub
- 💬 **Community**: Join our community discussions

---

**Gap Mesh** – _Decentralized • Private • Free_
