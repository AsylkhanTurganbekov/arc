# A.R.C. — AI Resource Command

A.R.C. — локальный command center для портфеля проектов BelesAI. Он читает Jira, рассчитывает здоровье проектов и спринтов, показывает нагрузку команды, риски релиза и сценарии перераспределения ресурсов. LLM не участвует в расчёте метрик и используется только в `Ask A.R.C.`.

## Запуск

1. Скопируйте `.env.example` в `.env` и заполните `JIRA_API_TOKEN`.
2. При необходимости добавьте новый `NITEC_LLM_API_KEY`. Без него весь dashboard работает, недоступен только AI-чат.
3. Запустите:

```bash
docker compose up -d --build
```

Откройте [http://localhost:3000](http://localhost:3000). Страница, backend API и AI API доступны через один локальный адрес.

Остановка:

```bash
docker compose down
```

## Что реализовано

- Morning Briefing и Portfolio/Project Health по 12 Jira-проектам;
- Sprint Health и People Load по активному спринту выбранного проекта;
- полный People Directory по портфелю: проекты, доли активной работы, загрузка и редактируемые позиции Backend/Frontend/QA/DevOps и другие;
- People Load считает активной разработкой только To Do/In Progress/Review/Blocked/Waiting; Test, Done Dev, Done Prod и Done в загрузку разработчика не входят;
- Stuck Tasks с проверкой changelog, Release Readiness и Delivery Management;
- What-if Simulator без записи изменений в Jira;
- AI Resource Planner, Weekly Review и Anomaly Detector;
- Ask A.R.C. через `llm.nitec.kz/v1`, модель `openai/gpt-oss-120b`, tool calling и история диалогов в PostgreSQL;
- ежедневные snapshots метрик в PostgreSQL;
- адаптивный интерфейс и рабочая навигация по всем разделам.

## Архитектура

- `frontend` — React/Vinext интерфейс;
- `backend` — Java 21 + Spring Boot, Jira-интеграция и детерминированная аналитика;
- `ai` — FastAPI, NITEC LLM Gateway и безопасные tools к backend;
- `postgres` — snapshots, диалоги и журналы tool calls;
- `gateway` — единая точка входа на `localhost:3000`.

## Проверка

После запуска контейнеров выполните:

```bash
./scripts/smoke-test.sh
```

Тест проверяет frontend, Jira-соединение, KIN/BEK, людей активного спринта, все аналитические API, AI health и What-if расчёт.

Дополнительные проверки:

```bash
npm test
cd backend && gradle test
```

Секреты хранятся только в локальном `.env`, который исключён из Git. Рекомендуется использовать отдельную Jira-учётную запись только для чтения.
