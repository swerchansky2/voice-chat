# UI Mockup - Voice Chat Desktop Client

## Connect Screen
```
┌─────────────────────────────────────────┐
│                                         │
│                                         │
│              Voice Chat                 │
│                                         │
│                                         │
│    ┌─────────────────────────────┐     │
│    │ Nickname                    │     │
│    │ [Enter your nickname...]    │     │
│    └─────────────────────────────┘     │
│                                         │
│    ┌─────────────────────────────┐     │
│    │ Server Host                 │     │
│    │ [localhost             ]    │     │
│    └─────────────────────────────┘     │
│                                         │
│    ┌─────────────────────────────┐     │
│    │ Server Port                 │     │
│    │ [8080                  ]    │     │
│    └─────────────────────────────┘     │
│                                         │
│    [Error: Connection failed]           │
│    (if error occurs)                    │
│                                         │
│    ┌─────────────────────────────┐     │
│    │        Connect              │     │
│    └─────────────────────────────┘     │
│                                         │
│                                         │
└─────────────────────────────────────────┘
        400x600 window
        Background: #36393F (dark gray)
        Accent: #5865F2 (blurple)
```

## Room Screen
```
┌─────────────────────────────────────────┐
│ ╔═══════════════════════════════════╗   │
│ ║         Voice Room                ║   │
│ ╚═══════════════════════════════════╝   │
├─────────────────────────────────────────┤
│                                         │
│  Users (3)                              │
│                                         │
│  ┌───────────────────────────────────┐ │
│  │ 🟢  Alice                         │ │
│  └───────────────────────────────────┘ │
│  ┌───────────────────────────────────┐ │
│  │ 🟢  Bob                           │ │
│  └───────────────────────────────────┘ │
│  ┌───────────────────────────────────┐ │
│  │ 🔇  Charlie (you)                 │ │
│  └───────────────────────────────────┘ │
│                                         │
│                                         │
│                                         │
│                                         │
│                                         │
│                                         │
├─────────────────────────────────────────┤
│  ┌────────────┐  ┌──────────────────┐  │
│  │   Unmute   │  │   Disconnect     │  │
│  └────────────┘  └──────────────────┘  │
└─────────────────────────────────────────┘
        400x600 window
        Header: #2F3136 (sidebar gray)
        Background: #36393F (dark gray)
        
        🟢 = Online (green #3BA55C)
        🔇 = Muted (gray #72767D)
        (you) = Current user (accent #5865F2)
```

## Color Scheme (Discord-style)
- **Background**: #36393F - Main background
- **Sidebar**: #2F3136 - Header/panels
- **Dark**: #202225 - Dividers
- **Text Primary**: #DCDDDE - Main text
- **Text Secondary**: #72767D - Dimmed text
- **Accent**: #5865F2 - Blurple (buttons, highlights)
- **Success**: #3BA55C - Green (online status)
- **Danger**: #ED4245 - Red (disconnect, errors)

## Navigation Flow
```
┌─────────────┐                    ┌──────────────┐
│   Connect   │ ─── Connect ────> │     Room     │
│   Screen    │                    │    Screen    │
│             │ <── Disconnect ─── │              │
└─────────────┘                    └──────────────┘
     │ ^                                  │
     │ │                                  │
     │ └── Error                          │
     │                                    │
     └── Enter nickname & server          └── Connected users
         info                                  Mute/Unmute
                                               Disconnect
```

## Component Hierarchy
```
Window
└── VoiceChatApp
    └── AppTheme
        ├── ConnectScreen (when disconnected)
        │   ├── TextField (Nickname)
        │   ├── TextField (Server Host)
        │   ├── TextField (Server Port)
        │   ├── Text (Error message)
        │   └── Button (Connect)
        │
        └── RoomScreen (when connected)
            ├── Box (Header)
            │   └── Text ("Voice Room")
            ├── Text ("Users (N)")
            ├── LazyColumn
            │   └── UserListItem (x N)
            │       ├── Box (Status dot)
            │       ├── Text (Nickname)
            │       └── Text (Mute icon)
            └── ControlPanel
                ├── Button (Mute/Unmute)
                └── Button (Disconnect)
```
