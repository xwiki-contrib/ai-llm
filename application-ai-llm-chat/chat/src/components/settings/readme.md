# chat-settings



<!-- Auto Generated Below -->


## Methods

### `getSettings() => Promise<{ llmServerAddress: string; selectedModel: string; temperature: number; jsonWebToken: string; stream: boolean; }>`



#### Returns

Type: `Promise<{ llmServerAddress: string; selectedModel: string; temperature: number; jsonWebToken: string; stream: boolean; }>`




## Dependencies

### Used by

 - [chat-pane](../pane)

### Depends on

- ion-card
- ion-card-content
- ion-item
- ion-input
- ion-label
- ion-select
- ion-select-option
- ion-range
- ion-toggle

### Graph
```mermaid
graph TD;
  chat-settings --> ion-card
  chat-settings --> ion-card-content
  chat-settings --> ion-item
  chat-settings --> ion-input
  chat-settings --> ion-label
  chat-settings --> ion-select
  chat-settings --> ion-select-option
  chat-settings --> ion-range
  chat-settings --> ion-toggle
  ion-card --> ion-ripple-effect
  ion-item --> ion-icon
  ion-item --> ion-ripple-effect
  ion-item --> ion-note
  ion-input --> ion-icon
  ion-select --> ion-select-popover
  ion-select --> ion-popover
  ion-select --> ion-action-sheet
  ion-select --> ion-alert
  ion-select --> ion-icon
  ion-select-popover --> ion-item
  ion-select-popover --> ion-checkbox
  ion-select-popover --> ion-radio-group
  ion-select-popover --> ion-radio
  ion-select-popover --> ion-list
  ion-select-popover --> ion-list-header
  ion-select-popover --> ion-label
  ion-popover --> ion-backdrop
  ion-action-sheet --> ion-backdrop
  ion-action-sheet --> ion-icon
  ion-action-sheet --> ion-ripple-effect
  ion-alert --> ion-ripple-effect
  ion-alert --> ion-backdrop
  ion-toggle --> ion-icon
  chat-pane --> chat-settings
  style chat-settings fill:#f9f,stroke:#333,stroke-width:4px
```

----------------------------------------------

*Built with [StencilJS](https://stenciljs.com/)*
