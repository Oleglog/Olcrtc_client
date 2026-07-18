# ТЗ — UI refresh v1.0.30: тёплая палитра, интереснее интерфейс, читаемые режимы, лёгкая статистика, частицы-дрифт

Статус: проект ТЗ для следующего релиза `Olcrtc_client`.

> Follow-up: требования к частицам и layout главного экрана изменены после device acceptance `v1.0.31`; актуальное продолжение — `CLIENT_UI_HOTFIX_V1_0_32.md`.

Базовая версия: `v1.0.28` (текущий HEAD `f21e676`, `versionCode=28`, `versionName=1.0.28`).
`v1.0.29` (P3 из `CLIENT_IMPROVEMENT_SPECIFICATION.md`) откатан пользователем по итогу.

Релиз-кандидат: `v1.0.30`.

Дата: 2026-07-18.

GitHub: https://github.com/Oleglog/Olcrtc_manager — релизная сборка запускается тегом клиента.

## 1. Назначение и что НЕ делает этот релиз

Только визуальная и структурная чистка UI. Никаких изменений datapath, lifecycle,
DNS, routing-логики, миграций Room, mobilecore, подписок. Сетевой путь и инварианты
из `CLIENT_IMPROVEMENT_SPECIFICATION.md` §3.1 не трогаются.

Запрещено в этом релизе:
- менять что-либо в `vpn/`, `subscription/`, `importer/`, `data/`, `routing/` (логика);
- трогать mobilecore inputs и CI cache;
- редактировать `CLAUDE.md` (проектные знания — в central vault).

Допустимо: правки `res/`, layout'ов, `colors.xml`, `dimens.xml`, `strings.xml`,
`themes.xml`, UI-кода во `fragments/` и `settings/AppSelectorActivity`, добавление
одного `View` для частиц и одной DataStore-настройки для toggle.

## 2. Пять целей = пять блоков

1. **Читаемые режимы per-app routing** — подписи `Все приложения / Все, кроме выбранных / Только выбранные` видны полностью, без троеточия.
2. **Лёгкая статистика** — убрать модуль «За месяц», оставить active + today + recent.
3. **Тёплая спокойная палитра** — surface кремовый/тёплый нейтральный + один тёплый акцент (bronze/amber). Мягче, чем текущий teal `#006B5E`, и спокойнее, чем indigo+amber из 1.0.29.
4. **Лёгкие частицы-дрифт** на главном экране, toggle в настройках, по умолчанию выключено. Drift, а не снег/дождь/искры из 1.0.29.
5. **UI-финиш** — карточки/кнопки/анимации чуть живее (elevation по state, status mark, ripple, crossfade текста кнопки, pulse при подключении), в строгом техническом ключе, без ярких заливок.

## 3. Блок A — Режимы per-app routing

### 3.1. Симптом

`activity_app_selector.xml`: `mode_group` — `MaterialButtonToggleGroup` в одну строку,
три кнопки `mode_all` / `mode_exclude` / `mode_only` делят ширину поровну (`weight=1`).
`maxLines=2`. На русском подписи длинные — squeeze'ятся, видны троеточия, смысл
режима («Все, кроме выбранных» vs «Только выбранные») не читается без нажатия.

### 3.2. Решение

Перевернуть `mode_group` из строки в столбец — триложенные кнопки каждая на свою высоту.

- `MaterialButtonToggleGroup` → `android:orientation="vertical"`.
- Каждая кнопка: `android:layout_width="match_parent"`, `android:layout_height="wrap_content"`, `android:gravity="start|center_vertical"`, `android:minHeight="52dp"`, `android:maxLines="2"`, `android:ellipsize="end"`.
- `weight` убрать (в столбце не нужен).
- Текст кнопок — оставить как есть (`settings_per_app_all/exclude/only`): подписи помещаются в две строки на всю ширину экрана, обрезаться не должны при font scale ≤ 1.3. При 1.5 — ellipsize допустим, режим всё равно понятен из preview.
- Под selector'ом добавить live-preview строку: «Через VPN: <формулировка> · выбрано N». Обновляется на change режима и изменения счётчика.

### 3.3. Приёмка

- На API 26, 31, 34 при RU и font scale 1.0 / 1.3 подписи всех трёх режимов читаются полностью, без `…`.
- `mode_all` / `mode_exclude` / `mode_only` остаются single-select; выбранный режим сохраняется в `PerAppPolicy.mode`.
- Toggle check визуально остаётся (Material `MaterialButtonToggleGroup` single-select в столбце работает штатно).
- Preview-строка корректна для каждого режима и обновляется при `selectAll`/`clear`.
- `settings_apps_only_empty` (Save при пустом `ONLY_SELECTED`) остаётся.

### 3.4. Файлы

- `app/src/main/res/layout/activity_app_selector.xml` — структура `mode_group`.
- `app/src/main/java/io/github/oleglog/olcrtc/client/settings/AppSelectorActivity.kt` — preview-строка, рендер счётчика.

## 4. Блок B — Лёгкая статистика

### 4.1. Симптом

`fragment_statistics.xml` имеет 4 карточки: `active_card`, `today_card`, `month_card`, `history_card`. Перегружено.

### 4.2. Решение

Убрать `month_card`. Оставить три блока в порядке:

1. `active_card` — активная сессия (крупно): профиль, длительность, трафик, скорость. Текущая `active_content` + `formatCurrentSession` остаются как есть.
2. `today_card` — сегодня: сессии, длительность, трафик (`formatTotals`).
3. `history_card` — последние сессии. Сейчас `recent.take(8)` — сократить до `recent.take(5)` (меньше строк, меньше шума).

Цветные reason-dots в `recentSessionRow` (8dp квадратик `disconnectColor`) — убрать. Причина завершения остаётся в тексте строки (`disconnectReasonLabel`) и в тап-диалоге `showReasonDialog`. Состояние не кодируется только цветом — правило a11y сохраняется.

### 4.3. Приёмка

- `StatisticsFragment`summary` больше не запрашивает и не рендерит `month`.
- `StatisticsSummary.month` и `formatTotals` для month — убрать из UI. Если `StatisticsSummary` в репо хранит `month` — поле можно оставить (минимальный diff), но в UI не выводить.
- `history_card` показывает ≤5 строк.
- Reason-dots нет; причина — текст в строке + диалог по тапу.
- `clear_history` (overflow/destructive) остаётся.
- Тicker `refreshCurrent` и `loadStatistics` остаются (логика активной сессии не меняется).

### 4.4. Файлы

- `app/src/main/res/layout/fragment_statistics.xml` — удалить `month_card`.
- `app/src/main/java/io/github/oleglog/olcrtc/client/statistics/StatisticsFragment.kt` — убрать `monthContent`, `recent.take(5)`, убрать reason-dot из `recentSessionRow`.

## 5. Блок C — Тёплая спокойная палитра

### 5.1. Принцип

- Surface — тёплый нейтральный (кремовый в light, тёплый тёмный в night), не зелёный и не холодный серый.
- Один основной accent — тёплый bronze/amber. Это замена teal `#006B5E`.
- Secondary — приглушённый тёплый, не контрастирует с primary.
- Error — тёплый красный, не яркий.
- Минимум декоративных заливок (по `CLIENT_IMPROVEMENT_SPECIFICATION.md` §19.1 — стиль строгий, технический, спокойный).

### 5.2. Целевые значения

`res/values/colors.xml` (light):

| Имя | Hex | Назначение |
|---|---|---|
| `olcrtc_primary` | `#8A6D3B` | тёплый bronze/amber accent |
| `olcrtc_on_primary` | `#FFFFFF` | текст на primary |
| `olcrtc_primary_container` | `#F0E4CF` | тёплый кремовый контейнер |
| `olcrtc_on_primary_container` | `#2C2418` | текст на контейнере |
| `olcrtc_secondary` | `#6F6354` | приглушённый тёплый |
| `olcrtc_on_secondary` | `#FFFFFF` | |
| `olcrtc_surface` | `#FBF7F1` | тёплый кремовый |
| `olcrtc_on_surface` | `#2B2620` | основной текст |
| `olcrtc_surface_variant` | `#EFE8DE` | тёплыйVariant |
| `olcrtc_on_surface_variant` | `#5C544A` | вторичный текст |
| `olcrtc_outline` | `#8E8678` | граница |
| `olcrtc_error` | `#9C3A2A` | тёплый тёмно-красный |
| `olcrtc_on_error` | `#FFFFFF` | |

`res/values-night/colors.xml` (dark):

| Имя | Hex |
|---|---|
| `olcrtc_primary` | `#C9A86A` (тёплый amber в dark — светлее для контраста на тёмном) |
| `olcrtc_on_primary` | `#3A2F1B` |
| `olcrtc_primary_container` | `#5A4A33` |
| `olcrtc_on_primary_container` | `#FBF0D8` |
| `olcrtc_secondary` | `#C2B8A8` |
| `olcrtc_on_secondary` | `#2E2820` |
| `olcrtc_surface` | `#1A1714` |
| `olcrtc_on_surface` | `#EDE7DE` |
| `olcrtc_surface_variant` | `#3A342D` |
| `olcrtc_on_surface_variant` | `#CFC6B8` |
| `olcrtc_outline` | `#928877` |
| `olcrtc_error` | `#FFB4AB` |
| `olcrtc_on_error` | `#690005` |

Точные hex — ориентир для реализации; финальные значения проверить по контрасту (текст ≥ 4.5:1, §20 a11y).

### 5.3. Где править

- `themes.xml` уже маcштабирует `Theme.Material3.DayNight.NoActionBar` и подставляет эти `colorPrimary`/`colorSurface`/etc. — после смены в `colors.xml` тема подхватит автоматически. `shapeAppearanceMediumComponent`/`Large` на 8dp остаются.
- Остальные экраны (`fragment_connection.xml`, `fragment_settings.xml`, `fragment_profiles.xml`, `fragment_statistics.xml`, `activity_app_selector.xml`) используют `?attr/colorSurface` / `?attr/colorPrimary` — перетекут автоматически. Отдельные хардкод-цвета — вычистить на `?attr/...` где найдутся.
- `StatisticsFragment.disconnectColor` использует `R.color.olcrtc_primary/error/secondary/outline` — эти имена сохраняются (меняются только hex-значения), поэтому код менять не нужно; رفت-эффект перетечёт.

### 5.4. Проверка

- Light и dark тема — оба режима.
- Контраст основного текста к surface ≥ 4.5:1 (light `#2B2620` на `#FBF7F1`, dark `#EDE7DE` на `#1A1714`).
- Активная карточка подключения (connection card) — не должна потерять «один accent border 2dp» (§10.3). Проверить, что accent остался читаемым на surface.
-_launcher icons / foreground (`ic_launcher_foreground.xml`) — не трогать, только системные цвета темы.

## 6. Блок D — Лёгкие частицы-дрифт

### 6.1. Что это

Один custom `View` поверх `profile_list`/`content_state` на главном экране (или root `fragment_connection`) — медленно дрейфующие точки.

- **Режим:** drift — частицы медленно движутся по диагонали, плавно оборачиваются по краям canvas. Никакого дождя/снега/испkр (то, что было в 1.0.29 P3, откатано).
- **Цвет частиц:** `?attr/colorPrimary` с низким alpha (~25–35%), чтобы не было «вырвиглазного» эффекта и не мешало читать карточки. Поверх карточек частицы **не рисуются** — слой под списком или с `alpha`-clip, не закрывает контент.
- **Количество:** ≤ 20 частиц, производительность.
- **Частота кадров:** стандартный `postInvalidateOnAnimation` / `Choreographer`, не выше системного vsync. При `Settings.Global.ANIMATOR_DURATION_SCALE == 0` (системная экономия анимаций) — не запускать (§19.5 — «учитывать системное уменьшение анимаций»).
- **Жизненный цикл:** рисуется только когда активен VPN (`CONNECTED`) либо на главном экране при видимости View. При `stop` / `DISCONNECTED` — снимается (visibility gone / stop invalidate), чтобы не было видимого движения без подключения и не расходовало батарею.

### 6.2. Toggle в настройках

- Новая настройка «Эффект фона» (`background_effects`) в `RoutingSettings` DataStore (boolean, по умолчанию `false`).
- В `fragment_settings.xml` добавить переключатель `MaterialSwitch` в группу «Система и фон» (существующая `settings_system_row` открывает actions-диалог) — либо отдельной строкой сверху группы. Решение за реализацией, но должна быть отдельная читаемая строка с понятным title.
- Toggle читается на старте `ConnectionFragment` (один `runBlocking`/`flow.first()` — по образцу `RoutingSettings.get()`).

### 6.3. Приёмка

- Частицы не видны, если toggle выключен (по умолчанию).
- Частицы не поверх контента, не мешают чтению карточек, не «вырвиглазные».
- При выключенной системной анимации их нет.
- При stop/disconnect движение прекращается (View снимается).
- Нет отдельного потока или busy-loop — один `View.onDraw` + `invalidate`, ≤20 элементов.
- Не добавляет новые external-зависимости в `build.gradle.kts`.

### 6.4. Файлы

- new `app/src/main/java/io/github/oleglog/olcrtc/client/ui/ParticleDriftView.kt` (или внутри connection-пакета) — custom View.
- `res/layout/fragment_connection.xml` — добавить view.
- `ConnectionFragment.kt` — читать toggle, управлять видимостью + lifecycle.
- `RoutingSettings.kt` — добавить `BACKGROUND_EFFECTS = booleanPreferencesKey("background_effects")` геттер.
- `SettingsFragment.kt` + `fragment_settings.xml` — toggle строка.
- `strings.xml` — `settings_background_effects` + summary.

## 7. Блок E — UI-финиш (блоки, кнопки, анимации)

Цель: интерфейс чуть интереснее и живее, не плоский, но в строгом техническом ключе
из §19.1. Никаких ярких заливок и тяжёлых анимаций. Точечные правки поверх текущего.

### 7.1. Карточки подключения (главный экран)

Сейчас (`ConnectionFragment.connectionCard`): `MaterialCardView`, `cardElevation=0`,
plain `colorSurface` фон, stroke по state (`olcrtc_primary` для selected/connected,
`colorOutline` для inactive), `corner_card=8dp`.

Что добавить:

- **Лёгкая elevation по state.** `CONNECTED` — `cardElevation = 2dp` + мягкая тень
  (`outlineAmbientShadowAlpha`, `outlineSpotShadowAlpha` можно оставить системными);
  `SELECTED` — `1dp`; `INACTIVE` — `0dp`. Эффект «подключённая карточка приподнята»,
  без фиолетовой/заливной заливки (§10.3 — не нарушаем).
- **Status mark слева.** Сейчас status только текстом в detail-строке. Добавить
  compact color mark слева от имени (узкая ~4dp вертикальная полоска accent'а или
  8dp dot), цветом по state: accent для connected, `colorOutline` для inactive.
  Состояние не **только** цветом (§20) — текст «Подключено» остаётся, mark дублирует.
- **Ripple на tap.** Карточка уже `isClickable`, но без явного ripple-фона. Поставить
  `?attr/selectableItemBackground` поверх surface — стандартный feedback тапа.
- **Transition на смену state.** При `INACTIVE → SELECTED → CONNECTED` анимировать
  `strokeWidth` и `cardElevation` через `ValueAnimator` (~150 мс, §19.5 — простая
  state transition 120–200 мс). Не блокировать UI (только визуальный polish).
- Padding карточки оставить как есть (`space_4/space_3/space_1/space_3`).

### 7.2. Иконки-кнопки (edit/delete)

Сейчас (`iconButton`): `AppCompatImageButton`, `selectableItemBackgroundBorderless`,
tint `colorOnSurfaceVariant`, size `icon_action_size=22dp`, touch target `icon_touch_target=48dp`.

Что добавить:
- Tint по hover/press: на press — tint `?attr/colorPrimary` (лёгкий feedback).
- Delete-кнопку не делать постоянно-красной (§10.4 — destructive только при press/confirm).
  Текущее состояние уже соответствует; лишь добавить `colorError` tint на press.
- Icon остаётся 22dp, touch target 48dp (§10.4) — не трогать.

### 7.3. Основная кнопка «Подключить/Отключить»

Сейчас (`fragment_connection.xml`): `MaterialButton`, `button_primary_height=56dp`,
`corner_button=8dp`, `?attr/materialButtonStyle` (filled).

Что добавить:
- При смене подписи (`Подключить → Отменить → Отключить → Повторить → Отключение…`)
  короткий crossfade текста (~120 мс). Минимально — через `ValueAnimator` alpha.
- Background tint по действию: «Отменить»/«Повторить» — оставить primary; «Отключить»
  контекста не требует смены цвета (не destructive), остаётся primary. **Не вводить**
  destructive-окраску на нейтральном disconnect (по §10.4 destructive только на delete).
- Лёгкий elevation-pulse на «Подключение…» состояние — `cardElevation`/`translationZ`
  pulse через `StateListAnimator` или простой `ObjectAnimator` (~600 мс in/out).
  Отключается при выключенной системной анимации (тот же guard, что у частиц, §6.1).

### 7.4. Bottom navigation + заголовки вкладок

- Bottom nav icons: уже vector 24dp (§19.4). Лёгкий Indicator transition — Material3
 已有. Не трогать, если уже штатное Material3 bar.
- Заголовки вкладок (`navigation_connection` и др.) — сейчас `textAppearanceHeadlineMedium`
  (settings/profiles/statistics) и `TitleLarge` (connection). Не выравниваем специально
  — оставляем. **Лёгкая нумерация/?** Нет — не добавляем капчу-номеров; стиль строгий.

### 7.5. Строки настроек (`fragment_settings.xml`)

Сейчас: outlined `MaterialButton` строки, `corner_button=8dp`, chevron в end.
Что добавить:
- Лёгкий ripple — уже есть (MaterialButton штатно).
- Анимация открытия/закрытия диалогов — оставить MaterialAlertDialogBuilder штатное.
- Не добавлять expansion-стрелок (это строки-actions, не раскрывающиеся группы).

### 7.6. Анимации — общее правило

- Все анимации: 120–200 мс (§19.5).
- Учитывать `Settings.Global.ANIMATOR_DURATION_SCALE == 0` — не запускать
  motion-анимации (общий guard; переиспользуется и для частиц блока D).
- Не блокировать действия декоративной анимацией — визуальный polish поверх
  синхронного state change.

### 7.7. Приёмка блока E

- CONNECTED-карточка визуально приподнята (elevation 2dp) и с status mark.
- Tap на карточку и icon-button даёт ripple feedback.
- Смена подписи основной кнопки — без жёсткого скачка (crossfade).
- Состояние «Подключение…» — мягкий pulse translationZ (если системная анимация вкл).
- Ни одной яркой/destructive-заливки на нейтральных элементах.
- Производительность: 50+ open/close циклов card appearance без утечек (§21.2 —
  визуальный слой не должен плодить objectAnimator'ы без cancel).

### 7.8. Файлы

- `app/src/main/java/io/github/oleglog/olcrtc/client/connection/ConnectionFragment.kt`
  — `connectionCard`, `applyCardAppearance`, `updateCardContent`, `iconButton`,
  основная кнопка `connect` текст/анимация.
- `app/src/main/res/layout/fragment_connection.xml` — статичные атрибуты кнопки.
- `app/src/main/res/anim/` (new) — опционально crossfade.xml для текста кнопки, если
  через resource, либо программный animator (решение за реализацией, минимальный diff).
- `app/src/main/res/animator/` (new) — `StateListAnimator` для elevation-pulse, если
  через resource; иначе программный `ObjectAnimator` в коде.



## 8. Реализационный порядок

Минимизировать смешивание — по одному блоку, по коммиту. Не один big-bang.

1. **Блок C (палитра)** — `colors.xml` × 2. Самый маленький diff, ниже всего риск. Коммит отдельно.
2. **Блок B (статистика)** — `fragment_statistics.xml` + `StatisticsFragment.kt`. Чисто UI, безопасно.
3. **Блок A (режимы)** — `activity_app_selector.xml` + `AppSelectorActivity.kt`. Структура + preview-логика.
4. **Блок E (UI-финиш)** — `ConnectionFragment.kt` + `fragment_connection.xml`. Карточки/кнопки/анимации.
5. **Блок D (частицы)** — самый объёмный (new view + lifecycle + settings toggle). Последним, после того как основной UI-слой стабилен.

Каждый блок проверяется локально (lint/визуально в IDE-превью). Реальные сборки — только GitHub Actions по тегу.

## 9. Definition of Done

Релиз `v1.0.30` готов, когда:

- [ ] Все 5 блоков реализованы по коммитам выше.
- [ ] Подписи режимов per-app routing читаются полностью на RU при font scale 1.0 / 1.3 (API 26/31/34).
- [ ] Модуль «За месяц» убран; статистика = active + today + ≤5 recent без reason-dots.
- [ ] Тёплая палитра (light + dark) дает контраст текста ≥ 4.5:1.
- [ ] Частицы-дрифт: по умолчанию выкл, toggle в настройках, не мешает контенту, не при включённой системной экономии анимаций, снимаются при stop.
- [ ] Карточки: CONNECTED приподнята (elevation 2dp) + status mark + ripple; смена подписи основной кнопки — crossfade; «Подключение…» — pulse (если системная анимация вкл); ни одной яркой заливки на нейтральных элементах.
- [ ] Datapath, DNS, routing-логика, подписки, импорт, mobilecore inputs — не тронуты.
- [ ] Существующие unit tests зелёные.
- [ ] Релиз запущен тегом `v1.0.30` в репо https://github.com/Oleglog/Olcrtc_manager; workflow зелёный; mobilecore из cache (без пересборки).
- [ ] Опубликованы arm64-v8a, armeabi-v7a, universal + `SHA256SUMS.txt`.
- [ ] Device acceptance: WBStream/Telemost connect, браузер/YouTube через VPN, per-app routing режимы переключаются и читаются, статистика без «За месяц», частицы включаются/выключаются через toggle и не видны при stop, карточки/кнопки дают ожидаемый feedback.
- [ ] Central vault `olcRTC/Project state.md` обновлён (TL;DR, Current stable = `Olcrtc_client v1.0.30`, one-liner в «Что свежее всего»).

## 9. Что вошло в v1.0.29 (P3) и НЕ переносим сюда

- indigo primary + amber secondary — заменено на тёплый bronze/amber single-accent.
- снег / дождь / искры — заменено на drift-частицы.
- (fast profile switch, history до 5 без точек, color redesign, ellipsize label'ов — частично совпадают с этим ТЗ по целям, но конкретные решения другие.)
