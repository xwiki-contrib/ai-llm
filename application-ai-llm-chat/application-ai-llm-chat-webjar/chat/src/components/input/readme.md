# chat-input



<!-- Auto Generated Below -->


## Events

| Event  | Description | Type                                          |
| ------ | ----------- | --------------------------------------------- |
| `send` |             | `CustomEvent<AssisterInputChangeEventDetail>` |


## Dependencies

### Used by

 - [chat-pane](../pane)

### Depends on

- ion-item
- ion-textarea
- ion-icon

### Graph
```mermaid
graph TD;
  chat-input --> ion-item
  chat-input --> ion-textarea
  chat-input --> ion-icon
  ion-item --> ion-icon
  ion-item --> ion-ripple-effect
  ion-item --> ion-note
  chat-pane --> chat-input
  style chat-input fill:#f9f,stroke:#333,stroke-width:4px
```

----------------------------------------------

*Built with [StencilJS](https://stenciljs.com/)*
