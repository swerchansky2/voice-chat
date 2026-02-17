# Voice Chat Server

MVP голосового чата — аналог Discord для личного использования. Проект на Kotlin с использованием современных инструментов.

## Архитектура

Сервер состоит из двух частей:
1. **WebSocket сигнальный сервер** — управление подключениями, комнатами, списком пользователей
2. **UDP Audio Relay** — приём аудио пакетов от клиентов и ретрансляция всем остальным участникам комнаты

## Технологии

- **Ktor 3.x** (Netty engine) — HTTP + WebSocket сервер
- **Kotlin 2.0+** с Coroutines — асинхронная обработка
- **Koin** — Dependency Injection
- **Kotlinx.serialization** — сериализация JSON сообщений
- **UDP сокеты** — для передачи аудио с низкой задержкой
- **Формат аудио** — Opus (определяется на уровне протокола, кодирование/декодирование на клиенте)

## Структура проекта

```
voice-chat/
├── server/          # Основной серверный модуль (Ktor + UDP)
├── shared/          # Общие протокольные классы (SignalMessage, AudioPacket)
└── client-desktop/  # Заглушка для будущего desktop клиента
```

## WebSocket протокол

### Клиент → Сервер:
```json
{"type": "join", "nickname": "Alice"}
{"type": "leave"}
{"type": "register_udp", "port": 12345}
```

### Сервер → Клиент:
```json
{"type": "joined", "userId": "abc-123"}
{"type": "user_list", "users": ["Alice", "Bob"]}
{"type": "user_joined", "nickname": "Bob"}
{"type": "user_left", "nickname": "Bob"}
{"type": "error", "message": "Nickname already taken"}
```

## UDP Audio Relay

- Сервер слушает UDP на порту **9001**
- Формат пакета: `[4 bytes: userId length][userId bytes][opus audio data]`
- При получении пакета сервер ретранслирует его всем участникам комнаты (кроме отправителя)
- Клиент отправляет `register_udp` через WebSocket, чтобы сервер знал его UDP адрес и порт

## Запуск

### Требования
- JDK 17 или выше

### Запуск сервера

```bash
./gradlew :server:run
```

Сервер запустится на:
- WebSocket: `ws://localhost:8080/ws/room`
- UDP: `0.0.0.0:9001`

### Настройка портов

Используйте переменные окружения:
```bash
HTTP_PORT=8080 UDP_PORT=9001 ./gradlew :server:run
```

## Сборка

```bash
# Собрать все модули
./gradlew build

# Собрать только сервер
./gradlew :server:build
```

## Разработка

- Логи выводятся в консоль через Logback
- Уровень логирования для `com.voicechat` — DEBUG
- Используется Koin для Dependency Injection
- Код следует Kotlin code style

## Аутентификация (MVP)

- Только по никнейму, без паролей
- Никнейм должен быть уникальным в комнате
- При дублировании возвращается ошибка

## Потокобезопасность

- Используется `ConcurrentHashMap` для хранения состояния комнат и пользователей
- Корутины для асинхронной обработки WebSocket и UDP
