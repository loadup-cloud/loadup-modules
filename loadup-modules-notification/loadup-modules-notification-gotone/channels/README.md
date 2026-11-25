# LoadUp Gotone Channel Binders

Each channel binder is an independent Maven artifact that registers one or more
`NotificationChannelProvider` beans. Add the binders you need; the engine discovers every
registered provider and builds its fallback chains automatically.

| Artifact                                  | Providers                        | Status                                 |
|-------------------------------------------|----------------------------------|----------------------------------------|
| `loadup-modules-notification-gotone-binder-email`   | `smtp` (Spring Mail)             | Production-ready                       |
| `loadup-modules-notification-gotone-binder-sms`     | `aliyun` / `huawei` / `yunpian`  | Stub (vendor SDK call pending)         |
| `loadup-modules-notification-gotone-binder-push`    | `fcm`                            | Stub (Firebase Admin SDK call pending) |
| `loadup-modules-notification-gotone-binder-webhook` | `dingtalk` / `wechat` / `feishu` | Production-ready (real HTTP)           |

Every provider can be disabled with `loadup.gotone.binder.<channel>.<provider>.enabled=false`.
Channel-level configuration (subject, template id, webhook URL, ...) is resolved at send time from
the `channelConfig` map of the matched `ChannelConfig`.

## 接入步骤

此目录是 Maven 聚合模块，不作为业务运行依赖。按用途选择子模块：

- [`loadup-modules-notification-gotone-binder-email`](loadup-modules-notification-gotone-binder-email/README.md)：后端实现。
- [`loadup-modules-notification-gotone-binder-push`](loadup-modules-notification-gotone-binder-push/README.md)：后端实现。
- [`loadup-modules-notification-gotone-binder-sms`](loadup-modules-notification-gotone-binder-sms/README.md)：后端实现。
- [`loadup-modules-notification-gotone-binder-webhook`](loadup-modules-notification-gotone-binder-webhook/README.md)：后端实现。

设计边界与装配路径见 [ARCHITECTURE.md](ARCHITECTURE.md)。
