# Радио

Интернет-радио для Android-магнитолы (экран 1024×600) и телефона.

- Эфир: избранное, название трека из потока (ICY/ID3), кодек и битрейт, буфер.
- Каталог станций из [Radio Browser](https://www.radio-browser.info): поиск, фильтр по России и жанрам.
- Недавние станции, таймер сна.
- Играет в фоне; кнопки на руле и магнитоле (вперёд/назад/пауза) работают через MediaSession.
- При обрыве связи переподключается сам.

**Скачать:** [Radio.apk](https://github.com/kuxnik/Radio/releases/latest/download/Radio.apk)

Каждый пуш в `main` собирает APK через GitHub Actions и кладёт его в Releases.
Все сборки подписаны одним ключом (`app/radio.jks`), поэтому ставятся поверх друг друга.

Стек: Kotlin, Jetpack Compose, Media3 (ExoPlayer + MediaSession), Coil.
