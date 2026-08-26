package com.iskeru.springai.claudecode.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.iskeru.springai.claudecode.ClaudeCodeChatModel;
import com.iskeru.springai.claudecode.ConversationRenderer;
import com.iskeru.springai.claudecode.DefaultConversationRenderer;
import com.iskeru.springai.claudecode.cli.ClaudeCodeCli;
import com.iskeru.springai.claudecode.cli.ProcessClaudeCodeCli;
import com.iskeru.springai.claudecode.replay.FileSystemFixtureStore;
import com.iskeru.springai.claudecode.replay.FixtureStore;
import com.iskeru.springai.claudecode.replay.ReplayMode;
import com.iskeru.springai.claudecode.replay.ReplayingClaudeCodeCli;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configures a {@link ClaudeCodeChatModel} backed by the local {@code claude} CLI.
 *
 * <p>
 * Intended for test slices: put it on the test classpath (or activate it with a test
 * profile) so the application's {@code ChatClient} talks to the developer's Claude Code
 * installation rather than a metered API. Every bean is
 * {@link ConditionalOnMissingBean @ConditionalOnMissingBean}, so any part of the chain —
 * the CLI executor, the fixture store, the conversation renderer — can be replaced by
 * declaring your own.
 */
@AutoConfiguration
@ConditionalOnClass({ ChatModel.class, ClaudeCodeChatModel.class })
@ConditionalOnProperty(prefix = ClaudeCodeChatProperties.PREFIX, name = "enabled", havingValue = "true",
		matchIfMissing = true)
@EnableConfigurationProperties(ClaudeCodeChatProperties.class)
public class ClaudeCodeChatAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	public FixtureStore claudeCodeFixtureStore(ClaudeCodeChatProperties properties,
			ObjectProvider<ObjectMapper> objectMapper) {
		return new FileSystemFixtureStore(properties.getReplay().getDirectory(),
				objectMapper.getIfAvailable(ObjectMapper::new));
	}

	@Bean
	@ConditionalOnMissingBean
	public ClaudeCodeCli claudeCodeCli(ClaudeCodeChatProperties properties, FixtureStore fixtureStore,
			ObjectProvider<ObjectMapper> objectMapper) {
		ReplayMode mode = properties.getReplay().getMode();

		// In REPLAY mode nothing is ever executed, so the CLI need not exist on this
		// machine at all — that is what lets CI run without Claude Code installed.
		ProcessClaudeCodeCli process = (mode == ReplayMode.REPLAY) ? null
				: ProcessClaudeCodeCli.builder()
					.executable(properties.getExecutable())
					.timeout(properties.getTimeout())
					.workingDirectory(properties.getWorkingDirectory())
					.environment(properties.getEnvironment())
					.sessionPersistence(properties.isSessionPersistence())
					.objectMapper(objectMapper.getIfAvailable(ObjectMapper::new))
					.build();

		if (mode == ReplayMode.LIVE) {
			return process;
		}
		return ReplayingClaudeCodeCli.builder().delegate(process).store(fixtureStore).mode(mode).build();
	}

	@Bean
	@ConditionalOnMissingBean
	public ConversationRenderer claudeCodeConversationRenderer() {
		return new DefaultConversationRenderer();
	}

	@Bean
	@ConditionalOnMissingBean
	public ClaudeCodeChatModel claudeCodeChatModel(ClaudeCodeChatProperties properties, ClaudeCodeCli cli,
			ConversationRenderer conversationRenderer) {
		return ClaudeCodeChatModel.builder()
			.cli(cli)
			.defaultOptions(properties.getOptions())
			.conversationRenderer(conversationRenderer)
			.build();
	}

}
