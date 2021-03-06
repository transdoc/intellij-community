// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.execution.segmentedRunDebugWidget

import com.intellij.application.subscribe
import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.executors.ExecutorGroup
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.stateExecutionWidget.StateWidgetProcess
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.messages.Topic
import org.jetbrains.annotations.ApiStatus
import java.util.function.Function

class StateWidgetManager(val project: Project) {
  @ApiStatus.Internal
  interface StateWidgetManagerListener {
    fun configurationChanged()
  }

  companion object {
    @JvmStatic
    fun getInstance(project: Project): StateWidgetManager = project.service()

    @Topic.AppLevel
    @ApiStatus.Internal
    @JvmField
    val TOPIC = Topic(StateWidgetManagerListener::class.java, Topic.BroadcastDirection.NONE)

    @JvmStatic
    fun fireConfigurationChanged() {
      ApplicationManager.getApplication().messageBus.syncPublisher(TOPIC).configurationChanged()
    }
  }

  private val processById: MutableMap<String, StateWidgetProcess> = mutableMapOf()
  private val processByExecutorId: MutableMap<String, StateWidgetProcess> = mutableMapOf()

  private val executions: MutableMap<Long, ExecutionEnvironment> = mutableMapOf()
  private val processIdByExecutionId: MutableMap<Long, String> = mutableMapOf()
  private val executionsByExecutorId: MutableMap<String, MutableSet<Long>> = mutableMapOf()

  private val activeProcessByConfiguration: MutableMap<RunnerAndConfigurationSettings?, MutableSet<StateWidgetProcess>> = mutableMapOf()
  private val executionByConfiguration: MutableMap<RunnerAndConfigurationSettings?, MutableSet<Long>> = mutableMapOf()

  init {
    ApplicationManager.getApplication().invokeLater {
      if(Disposer.isDisposed(project)) return@invokeLater

      val processes = StateWidgetProcess.getProcesses()
      processes.forEach {
        processById[it.ID] = it
      }

      ExecutionManager.EXECUTION_TOPIC.subscribe(project, object : ExecutionListener {
        override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
          ExecutorGroup.getGroupIfProxy(env.executor)?.let { executorGroup ->
            processes.forEach { process ->
              if (process.executorId == executorGroup.id) {
                collect(process, executorId, env)
                return
              }
            }
            return
          }

          processes.forEach { process ->
            if (process.executorId == executorId) {
              collect(process, executorId, env)
              return
            }
          }
        }

        private fun collect(process: StateWidgetProcess, executorId: String, env: ExecutionEnvironment) {
          executions[env.executionId] = env
          executionsByExecutorId.computeIfAbsent(executorId, Function { mutableSetOf() }).add(env.executionId)
          processIdByExecutionId[env.executionId] = process.ID
          processByExecutorId[executorId] = process
          executionByConfiguration.computeIfAbsent(env.runnerAndConfigurationSettings, Function { mutableSetOf() }).add(env.executionId)
          update()
        }

        override fun processTerminated(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler, exitCode: Int) {
          executions.remove(env.executionId)
          executionsByExecutorId[executorId]?.let {
            it.remove(env.executionId)
            if (it.isEmpty()) executionsByExecutorId.remove(executorId)
          }
          processByExecutorId.remove(executorId)
          processIdByExecutionId.remove(env.executionId)
          executionByConfiguration[env.runnerAndConfigurationSettings]?.remove(env.executionId)
          update()
        }

        private fun update() {
          updateRunningConfiguration()
          fireConfigurationChanged()
        }

        private fun updateRunningConfiguration() {
          activeProcessByConfiguration.clear()

          executions.values.forEach { env ->
            processByExecutorId[env.executor.id]?.let { process ->
              activeProcessByConfiguration.computeIfAbsent(env.runnerAndConfigurationSettings, Function { mutableSetOf() }).add(process)
            }
          }
        }
      })
    }
  }

  fun getExecutionsCount(): Int {
    return executions.count()
  }

  fun getActiveProcessesIDs(): Set<String> {
    return getActiveProcesses().map { it.ID }.toSet()
  }

  fun getActiveProcesses(): Set<StateWidgetProcess> {
    return executionsByExecutorId.keys.mapNotNull { processByExecutorId[it] }.toSet()
  }

  fun getActiveProcessesBySettings(configuration: RunnerAndConfigurationSettings): Set<StateWidgetProcess>? {
    return activeProcessByConfiguration[configuration]
  }

  fun getExecutionBySettings(configuration: RunnerAndConfigurationSettings): Set<ExecutionEnvironment>? {
    return executionByConfiguration[configuration]?.mapNotNull { executions[it] }?.toSet()
  }

  fun getExecutionByExecutionId(executionId: Long): ExecutionEnvironment? {
    return executions[executionId]
  }

  fun getProcessByExecutionId(executionId: Long): StateWidgetProcess? {
    return processIdByExecutionId[executionId]?.let { processById[it] }
  }

  fun getActiveExecutionEnvironments(): Set<ExecutionEnvironment> {
    return executions.values.toSet()
  }
}