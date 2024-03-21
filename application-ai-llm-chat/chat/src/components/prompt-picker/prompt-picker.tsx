import { Component, State, h } from '@stencil/core';

@Component({
  tag: 'prompt-picker',
  styleUrl: 'prompt-picker.css',
  shadow: true,
})
export class PromptPicker {
  @State() prompts: Array<{ id: string; name: string; description: string; default: boolean; temperature: number }> = [];
  @State() selectedPromptId: string = '';
  @State() isLoading: boolean = true;
  @State() error: string = '';

  async componentWillLoad() {
    // Access XWikiAiAPI from the global window object
    const api = window['XWikiAiAPI'];

    if (api) {
      try {
        this.isLoading = true;
        const promptsData = await api.getPrompts();
        // Proceed with mapping and setting the state as before
        this.prompts = promptsData.map(prompt => ({
          id: prompt.xwikiPageName,
          name: prompt.name,
          description: prompt.description,
          default: prompt.default,
          temperature: prompt.temperature
        }));
        const defaultPrompt = this.prompts.find(prompt => prompt.default);
        if (defaultPrompt) {
          this.selectedPromptId = defaultPrompt.id;
        }
        this.isLoading = false;
      } catch (error) {
        this.error = error.message;
        this.isLoading = false;
      }
    } else {
      this.error = 'XWikiAiAPI is not available.';
      this.isLoading = false;
    }
  }

  render() {
    return (
      <ion-item>
        {this.isLoading ? (
          <ion-label>Loading prompts...</ion-label>
        ) : this.error ? (
          <ion-label>Error: {this.error}</ion-label>
        ) : (
          <ion-select
            value={this.selectedPromptId}
            onIonChange={e => this.selectedPromptId = e.detail.value}
            placeholder="Prompt DB"
          >
            {this.prompts.map(prompt => (
              <ion-select-option value={prompt.id}>{prompt.name}</ion-select-option>
            ))}
          </ion-select>
        )}
      </ion-item>
    );
  }
}

