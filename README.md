# AI Chat — Multi-Provider Android Client

[Русский](#русский) | [English](#english)

---

## Русский

**AI Chat** — нативный Android-клиент на Kotlin и Jetpack Compose (Material 3) для работы с языковыми и мультимодальными моделями через единый интерфейс без рекламы, трекеров и сторонней аналитики.

### Поддерживаемые провайдеры
Coarena, OpenAI, Perplexity, Anthropic, Claude Code, Google Gemini, DeepSeek, Groq, OpenRouter, Grok, Mistral AI, Together AI, Cloudflare AI, Hugging Face, xAI и любой собственный OpenAI-совместимый сервер (Ollama, LM Studio, vLLM, LiteLLM и др.).

### Как добавить провайдера и где получить API key
1. На первом экране **«Добавьте провайдеров»** (или во вкладке **«Провайдеры»**) выберите нужного провайдера или нажмите **«Добавить аккаунт»** для создания второго профиля/ключа того же провайдера.
2. Получите API key в личном кабинете выбранного сервиса (OpenAI Platform, Anthropic Console, Google AI Studio, OpenRouter, Groq Console, DeepSeek Platform, Mistral Console, Together AI, Cloudflare Dashboard, Hugging Face Settings, xAI Console, Perplexity Settings или у администратора вашего сервера).
3. Введите ключ в поле **API key** — ключ шифруется локально с помощью **Android Keystore (AES-256-GCM)** и никогда не сохраняется в открытом виде и не попадает в логи.
4. Нажмите **«Проверить подключение»**, затем **«Загрузить модели»** (или введите ID модели вручную, если сервер не предоставляет `/models`).

### Как выбрать режим API (OpenAI-compatible vs Anthropic-compatible)
- **OpenAI-compatible**: выбирайте для OpenAI, OpenRouter, Groq, Together AI, Mistral, DeepSeek, xAI, Coarena, Google Gemini (OpenAI endpoint) и большинства локальных серверов (`POST {baseUrl}/chat/completions`).
- **Anthropic-compatible**: выбирайте для Anthropic / Claude, если endpoint ожидает Anthropic Messages API (`POST {baseUrl}/v1/messages`, заголовки `x-api-key` и `anthropic-version`).
- **Автоопределение**: автоматически определяет протокол по URL и ответу endpoint.
- Если не уверены — начните с **OpenAI-compatible**.

### Как добавить собственный OpenAI-совместимый сервер
1. Откройте карточку **«Свой OpenAI-совместимый сервер»**.
2. Укажите произвольный **Base URL** (например, `http://192.168.1.100:11434/v1` для Ollama или `https://llm.example.com/v1`).
3. При необходимости добавьте **API key** и **Опциональные заголовки**.
4. Проверьте подключение и загрузите модели (или добавьте ID модели вручную).

### Изображения, документы и ссылки
- Нажмите значок **скрепки** рядом с полем ввода:
  - **Изображение из галереи** (через системный Android Photo Picker без широких разрешений).
  - **Сделать фото** (запрашивает разрешение камеры только в момент съёмки).
  - **Документ (PDF, TXT, MD)**: извлекает текст локально (для PDF/TXT/Markdown) либо передаёт документ поддерживаемым моделям.
- При вводе или вставке **URL** в текст сообщения появится кнопка **«Добавить как вложение»**. Если в Настройках включена опция **«Загружать текст веб-страниц локально»**, приложение извлечёт заголовок и основной текст страницы перед отправкой модели.
- Если выбранная модель не поддерживает тип вложения, отобразится предупреждение с быстрым выбором совместимой модели.

### Сохранение сгенерированных изображений и видео
- Нажмите на сгенерированное изображение для открытия в полноэкранном просмотрщике с зумом и свайпами, либо используйте кнопки **«Сохранить»**, **«Поделиться»**, **«Копировать ссылку»**, **«Повторить»**.
- Сгенерированные видео воспроизводятся прямо в чате через **Media3 ExoPlayer** с кнопками **«Открыть»**, **«Сохранить»**, **«Поделиться»** и **«Повторить»**.
- Сохранение в системную галерею использует `MediaStore` (на Android 10+ без дополнительных разрешений).

### Сборка Debug и Release
```bash
# Декодирование debug.keystore (если отсутствует локально)
[ -f debug.keystore ] || base64 -d debug.keystore.base64 > debug.keystore

# Проверка lint и unit-тестов
gradle :app:lintDebug :app:testDebugUnitTest

# Debug APK
gradle :app:assembleDebug

# Release APK и AAB (требуются переменные окружения подписи)
export KEYSTORE_PATH=/path/to/upload-keystore.jks
export STORE_PASSWORD=...
export KEY_PASSWORD=...
gradle :app:assembleRelease :app:bundleRelease
```

### Необходимые GitHub Secrets для CI
В **Settings → Secrets and variables → Actions** добавьте:
- `ANDROID_KEYSTORE_BASE64` — keystore в формате Base64 (`base64 -w 0 release.jks`).
- `ANDROID_KEYSTORE_PASSWORD` — пароль хранилища ключей.
- `ANDROID_KEY_ALIAS` — alias ключа (по умолчанию `upload` в `signingConfigs`).
- `ANDROID_KEY_PASSWORD` — пароль ключа.

### Отчёты об ошибках и диагностика
При ошибке запроса прямо в карточке сообщения отображаются объяснение, HTTP-статус, код ошибки, URL (без ключа) и кнопка **«Скопировать ошибку»**. Она копирует отформатированный отчёт (время ISO 8601, провайдер, модель, endpoint, HTTP-статус, код, сообщение, Request ID и технические детали) с автоматическим удалением любых API-ключей.

---

## English

**AI Chat** is a native Kotlin & Jetpack Compose (Material 3) Android client for conversing with multiple AI providers through a clean, fast, privacy-first interface with zero ads, trackers, or telemetry.

### Supported Providers
Coarena, OpenAI, Perplexity, Anthropic, Claude Code, Google Gemini, DeepSeek, Groq, OpenRouter, Grok, Mistral AI, Together AI, Cloudflare AI, Hugging Face, xAI, and Custom OpenAI-compatible servers (Ollama, LM Studio, vLLM, LiteLLM, etc.).

### Adding a Provider & Obtaining an API Key
1. On the initial **Add Providers** screen (or **Providers** tab), pick any provider or tap **Add Account** to configure multiple keys/accounts for the same provider.
2. Create an API key in your provider's developer console (OpenAI, Anthropic, Google AI Studio, OpenRouter, Groq, DeepSeek, Mistral, Together AI, Cloudflare, Hugging Face, xAI, Perplexity, or your self-hosted server).
3. Paste the key into **API Key**. Keys are encrypted locally with **Android Keystore (AES-256-GCM)** and never logged or stored in plaintext SharedPreferences.
4. Tap **Test Connection**, then **Fetch Models** (or enter a model ID manually if the endpoint does not expose `/models`).

### Choosing OpenAI-compatible vs Anthropic-compatible Mode
- **OpenAI-compatible**: Choose for OpenAI, OpenRouter, Groq, Together AI, Mistral, DeepSeek, xAI, Coarena, Google Gemini (OpenAI endpoint), and most custom servers (`POST {baseUrl}/chat/completions`).
- **Anthropic-compatible**: Choose for Anthropic/Claude when the endpoint expects the Anthropic Messages API (`POST {baseUrl}/v1/messages` with `x-api-key` and `anthropic-version`).
- **Auto-detect**: Resolves the protocol automatically based on the Base URL and probe response.
- When unsure, start with **OpenAI-compatible**.

### Adding a Custom OpenAI-Compatible Server
1. Select **Custom OpenAI-compatible Server**.
2. Enter your custom **Base URL** (e.g., `http://192.168.1.100:11434/v1` or `https://llm.yourdomain.com/v1`).
3. Optionally configure an **API Key** and **Optional Headers**.
4. Test the connection and fetch or manually add model IDs.

### Sending Images, Documents, and Web Links
- Tap the **paperclip** icon in the chat composer:
  - **Photo from Gallery**: uses the zero-permission Android Photo Picker.
  - **Take Photo**: requests `CAMERA` permission only when capturing.
  - **Document (PDF, TXT, MD)**: extracts text locally when possible or attaches supported document payloads.
- Typing or pasting a **URL** shows an **Add as attachment** chip. Enable **Fetch Web Page Text Locally** in Settings to download page title and text locally before sending.
- If the active model lacks attachment support, the app warns you and lets you switch to a compatible model in one tap.

### Saving Generated Images and Videos
- Tap generated images to inspect them in the zoomable fullscreen viewer, or use **Save**, **Share**, **Copy Link**, and **Retry**.
- Generated videos play inline via **Media3 ExoPlayer** with **Open**, **Save**, **Share**, and **Retry** controls.
- Media saving uses `MediaStore` with minimal permissions.

### Building Debug & Release
```bash
[ -f debug.keystore ] || base64 -d debug.keystore.base64 > debug.keystore
gradle :app:lintDebug :app:testDebugUnitTest
gradle :app:assembleDebug
gradle :app:assembleRelease :app:bundleRelease
```

### Required GitHub Secrets
Configure these repository secrets for CI release signing:
- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS` (must be `upload` to match `signingConfigs`)
- `ANDROID_KEY_PASSWORD`

### Reporting Errors & Copying Diagnostics
Every failed message displays an actionable explanation, HTTP status, error code, sanitized endpoint URL, and a **Copy Error** button that exports a structured diagnostic report with all API keys scrubbed.
