package com.iskeru.springai.claudecode.autoconfigure;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.iskeru.springai.claudecode.ClaudeCodeChatModel;
import com.iskeru.springai.claudecode.ClaudeCodeChatOptions;
import com.iskeru.springai.claudecode.ConversationRenderer;
import com.iskeru.springai.claudecode.cli.ClaudeCodeCli;
import com.iskeru.springai.claudecode.cli.ProcessClaudeCodeCli;
import com.iskeru.springai.claudecode.cli.RecordingFakeCli;
import com.iskeru.springai.claudecode.replay.FileSystemFixtureStore;
import com.iskeru.springai.claudecode.replay.FixtureStore;
import com.iskeru.springai.claudecode.replay.ReplayMode;
import com.iskeru.springai.claudecode.replay.ReplayingClaudeCodeCli;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import com.iskeru.springai.claudecode.cli.IsolationPolicy;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudeCodeChatAutoConfigurationTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(ClaudeCodeChatAutoConfiguration.class));

	@Test
	void registersALiveModelByDefault() {
		this.runner.run(context -> {
			assertThat(context).hasSingleBean(ClaudeCodeChatModel.class);
			assertThat(context).getBean(ClaudeCodeCli.class).isInstanceOf(ProcessClaudeCodeCli.class);
		});
	}

	@Test
	void backsOffWhenDisabled() {
		this.runner.withPropertyValues("spring.ai.claude-code.enabled=false")
			.run(context -> assertThat(context).doesNotHaveBean(ClaudeCodeChatModel.class));
	}

	@Test
	void bindsChatOptions() {
		this.runner
			.withPropertyValues("spring.ai.claude-code.options.model=opus",
					"spring.ai.claude-code.options.effort=high",
					"spring.ai.claude-code.options.system-prompt=Be terse.",
					"spring.ai.claude-code.options.isolation=custom",
					"spring.ai.claude-code.options.tools=Read,Grep")
			.run(context -> {
				ClaudeCodeChatOptions options = context.getBean(ClaudeCodeChatProperties.class).getOptions();
				assertThat(options.getModel()).isEqualTo("opus");
				assertThat(options.getEffort()).isEqualTo("high");
				assertThat(options.getSystemPrompt()).isEqualTo("Be terse.");
				assertThat(options.getTools()).containsExactly("Read", "Grep");
				assertThat(options.getIsolation()).isEqualTo(IsolationPolicy.CUSTOM);
			});
	}

	@Test
	void bindsProcessSettings() {
		this.runner
			.withPropertyValues("spring.ai.claude-code.executable=/opt/claude",
					"spring.ai.claude-code.timeout=90s", "spring.ai.claude-code.session-persistence=true",
					"spring.ai.claude-code.max-stdout-bytes=2048", "spring.ai.claude-code.max-stderr-bytes=256")
			.run(context -> {
				ClaudeCodeChatProperties properties = context.getBean(ClaudeCodeChatProperties.class);
				assertThat(properties.getExecutable()).isEqualTo("/opt/claude");
				assertThat(properties.getTimeout()).isEqualTo(Duration.ofSeconds(90));
				assertThat(properties.isSessionPersistence()).isTrue();
				assertThat(properties.getMaxStdoutBytes()).isEqualTo(2048);
				assertThat(properties.getMaxStderrBytes()).isEqualTo(256);
			});
	}

	@Test
	void wrapsTheCliWhenReplayIsEnabled() {
		this.runner
			.withPropertyValues("spring.ai.claude-code.replay.mode=auto",
					"spring.ai.claude-code.replay.directory=target/test-fixtures")
			.run(context -> {
				assertThat(context).getBean(ClaudeCodeCli.class).isInstanceOf(ReplayingClaudeCodeCli.class);
				assertThat(context.getBean(ReplayingClaudeCodeCli.class).getMode()).isEqualTo(ReplayMode.AUTO);
				assertThat(context.getBean(FileSystemFixtureStore.class).getDirectory().toString())
					.endsWith("/target/test-fixtures");
			});
	}

	@Test
	void replayModeBuildsWithoutRequiringTheCliToBeInstalled() {
		this.runner
			.withPropertyValues("spring.ai.claude-code.replay.mode=replay",
					"spring.ai.claude-code.executable=/nonexistent/claude")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).getBean(ClaudeCodeCli.class).isInstanceOf(ReplayingClaudeCodeCli.class);
				assertThat(context).doesNotHaveBean(ProcessClaudeCodeCli.class);
			});
	}

	@Test
	void aUserSuppliedCliReplacesTheAutoConfiguredOne() {
		this.runner.withUserConfiguration(CustomCliConfiguration.class).run(context -> {
			assertThat(context).getBean(ClaudeCodeCli.class).isInstanceOf(RecordingFakeCli.class);
			assertThat(context).hasSingleBean(ClaudeCodeChatModel.class);
			assertThat(context.getBean(ClaudeCodeChatModel.class).call("hi")).isEqualTo("ok");
		});
	}

	@Test
	void aUserSuppliedRendererAndStoreReplaceTheDefaults() {
		this.runner.withUserConfiguration(CustomCollaboratorsConfiguration.class).run(context -> {
			assertThat(context).doesNotHaveBean(FileSystemFixtureStore.class);
			assertThat(context.getBean(ConversationRenderer.class).render(null).userPrompt()).isEqualTo("custom");
		});
	}

	@Test
	void backsOffForAnExistingChatModel() {
		ChatModel existing = prompt -> null;
		this.runner.withBean("existingProvider", ChatModel.class, () -> existing).run(context -> {
			assertThat(context).hasSingleBean(ChatModel.class).doesNotHaveBean(ClaudeCodeChatModel.class);
			assertThat(context.getBean(ChatModel.class)).isSameAs(existing);
		});
	}

	@Test
	void explicitTestProfileDisablesTheOpenAiStarterAndUsesTheCli() {
		new ApplicationContextRunner()
			.withUserConfiguration(ProviderProfileConfiguration.class, CustomCliConfiguration.class)
			.withPropertyValues("spring.ai.model.chat=none", "spring.ai.model.embedding=none",
					"spring.ai.model.image=none", "spring.ai.model.moderation=none",
					"spring.ai.model.audio.speech=none", "spring.ai.model.audio.transcription=none")
			.run(context -> {
				assertThat(context).hasNotFailed().hasSingleBean(ChatModel.class).hasSingleBean(ClaudeCodeChatModel.class)
					.doesNotHaveBean(OpenAiChatModel.class);
				assertThat(context.getBean(ChatClient.Builder.class).build().prompt("hi").call().content()).isEqualTo("ok");
				assertThat(((RecordingFakeCli) context.getBean(ClaudeCodeCli.class)).getOnlyRequest().isolation())
					.isEqualTo(IsolationPolicy.ISOLATED);
			});
	}

	@Configuration(proxyBeanMethods = false)
	@EnableAutoConfiguration
	static class ProviderProfileConfiguration {
	}

	@Configuration(proxyBeanMethods = false)
	static class CustomCliConfiguration {

		@Bean
		ClaudeCodeCli claudeCodeCli() {
			return new RecordingFakeCli();
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class CustomCollaboratorsConfiguration {

		@Bean
		ConversationRenderer conversationRenderer() {
			return messages -> new ConversationRenderer.RenderedPrompt(null, "custom");
		}

		@Bean
		FixtureStore fixtureStore() {
			return new FixtureStore() {
				@Override
				public java.util.Optional<com.iskeru.springai.claudecode.cli.ClaudeCodeCliResponse> load(String key) {
					return java.util.Optional.empty();
				}

				@Override
				public void save(String key, com.iskeru.springai.claudecode.cli.ClaudeCodeCliRequest request,
						com.iskeru.springai.claudecode.cli.ClaudeCodeCliResponse response) {
				}

				@Override
				public String describe() {
					return "in-memory";
				}
			};
		}

	}

}
