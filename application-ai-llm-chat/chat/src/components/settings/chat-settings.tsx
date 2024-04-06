import { Component, h, State, Method, Element } from '@stencil/core';

@Component({
  tag: 'chat-settings',
  styleUrl: 'chat-settings.css',
  shadow: true,
})
export class ChatSettings {
  @Element() el: HTMLElement;
  
  @State() llmServerAddress: string = '';
  @State() selectedModel: string = '';
  @State() temperature: number = 1;
  @State() stream: boolean = true;
  @State() models: Array<{ id: string; name: string }> = [];

  private settingsKey = 'chatAppSettings';

  async componentWillLoad() {
    this.loadSettings();
    await this.fetchModels();
  }

  componentDidLoad() {
    // Use this.el to dispatch the event
    const event = new CustomEvent('settingsLoaded', {
      detail: { settings: this.getSettings() },
      bubbles: true,
      composed: true,
    });
    this.el.dispatchEvent(event);
  }


  async fetchModels() {
    // Access XWikiAiAPI from the global window object
    const api = window['XWikiAiAPI'];
    if (api) {
      try {  
        const response = await api.getModels();
        this.models = response.data.map(model => ({
          id: model.id,
          name: model.name,
          // Add any other necessary model properties here
        }));
      } catch (error) {
        console.error('Failed to fetch models:', error);
        // Handle error appropriately
      }
    }
  }

  handleLLMServerAddressChange(event: CustomEvent) {
    this.llmServerAddress = event.detail.value;
    this.saveSettings();
  }

  handleSelectedModelChange(event: CustomEvent) {
    this.selectedModel = event.detail.value;
    this.saveSettings();
  }

  handleTemperatureChange(value: number) {
    this.temperature = value;
    this.saveSettings();
  }

  handleTemperatureInputChange(event: Event) {
    const value = parseFloat((event.target as HTMLInputElement).value);
    this.temperature = value;
    this.saveSettings();
  }

  handleStreamChange(event: CustomEvent) {
    this.stream = event.detail.checked;
    this.saveSettings();
  }

  @Method()
    async getSettings() {
        return {
        llmServerAddress: this.llmServerAddress,
        selectedModel: this.selectedModel,
        temperature: this.temperature,
        stream: this.stream,
        };
    }

  render() {
    return (
      <div>
        <ion-card>
          <ion-card-content class="settings-header">
          SETTINGS
          </ion-card-content>
        </ion-card>
        <ion-item>
          <ion-input
            label='Server Address:'
            value={this.llmServerAddress}
            onIonChange={e => this.handleLLMServerAddressChange(e)}
          ></ion-input>
        </ion-item>
        <ion-item>
          <ion-label>Model:</ion-label>
          <ion-select
            value={this.selectedModel}
            onIonChange={e => this.handleSelectedModelChange(e)}
          >
            {this.models.length > 0 ? (
              this.models.map(model => (
                <ion-select-option value={model.id}>{model.name}</ion-select-option>
              ))
            ) : (
              <ion-select-option disabled>Loading models...</ion-select-option>
            )}
          </ion-select>
        </ion-item>
        <ion-item>
          <div class="temperature-container">
              <ion-range 
              label='Temp:'
              min={0} max={2} step={0.01} value={this.temperature}
              pin={false} snaps={true} onIonChange={e => this.handleTemperatureChange(e.detail.value as number)}
              class="temperature-range">
              <ion-label slot="start">0</ion-label>
              <ion-label slot="end">2</ion-label>
              </ion-range>
              <ion-input type="number" value={this.temperature.toString()}
              onIonChange={e => this.handleTemperatureInputChange(e)}
              class="temperature-input"></ion-input>
          </div>
        </ion-item>
        <ion-item>
            <ion-label class="stream-label">Stream</ion-label>
            <ion-toggle checked={this.stream} onIonChange={e => this.handleStreamChange(e)}></ion-toggle>
        </ion-item>
      </div>
    );
}


  saveSettings() {
    const settings = {
      llmServerAddress: this.llmServerAddress,
      selectedModel: this.selectedModel,
      temperature: this.temperature,
      stream: this.stream,
    };
    localStorage.setItem(this.settingsKey, JSON.stringify(settings));
  }

  loadSettings() {
    const settingsString = localStorage.getItem(this.settingsKey);
    if (settingsString) {
      const settings = JSON.parse(settingsString);
      this.llmServerAddress = settings.llmServerAddress;
      this.selectedModel = settings.selectedModel;
      this.temperature = settings.temperature;
      this.stream = settings.stream;
    }
  }
}
