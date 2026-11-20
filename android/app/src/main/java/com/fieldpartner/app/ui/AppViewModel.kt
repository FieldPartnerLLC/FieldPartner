package com.fieldpartner.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fieldpartner.app.FieldPartnerApp
import com.fieldpartner.app.core.ApiException
import com.fieldpartner.app.core.CompanyInfo
import com.fieldpartner.app.core.Job
import com.fieldpartner.app.core.TradeProfileRow
import com.fieldpartner.app.core.UserInfo
import com.fieldpartner.app.visit.VisitController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Screens in the app. Kept as a tiny stack instead of a navigation library. */
sealed class Screen {
  data object Login : Screen()
  data object Jobs : Screen()
  data class JobDetail(val job: Job) : Screen()
  data object WalkIn : Screen()
  data object Settings : Screen()
  data object Visit : Screen()
  data class Report(val visitId: String) : Screen()
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
  val app = application as FieldPartnerApp

  data class State(
    val user: UserInfo? = null,
    val company: CompanyInfo? = null,
    val trades: List<TradeProfileRow> = emptyList(),
    val jobs: List<Job> = emptyList(),
    val loadingJobs: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val stack: List<Screen> = listOf(Screen.Login),
  ) {
    val screen: Screen
      get() = stack.last()
  }

  private val _state = MutableStateFlow(State())
  val state: StateFlow<State> = _state.asStateFlow()

  var visit: VisitController? = null
    private set

  init {
    if (app.api.token != null) {
      _state.update { it.copy(stack = listOf(Screen.Jobs)) }
      refreshMe()
    }
  }

  fun push(s: Screen) = _state.update { it.copy(stack = it.stack + s, error = null) }

  fun back(): Boolean {
    val st = _state.value
    if (st.screen is Screen.Visit) return false // ending a visit is explicit
    if (st.stack.size <= 1) return false
    _state.update { it.copy(stack = it.stack.dropLast(1), error = null) }
    return true
  }

  fun clearError() = _state.update { it.copy(error = null) }

  fun login(email: String, password: String, server: String) {
    viewModelScope.launch {
      _state.update { it.copy(busy = true, error = null) }
      try {
        if (server.isNotBlank() && server != app.settings.current.apiBaseUrl) app.setBaseUrl(server)
        val r = app.api.login(email.trim(), password)
        app.setToken(r.token)
        _state.update { it.copy(user = r.user, company = r.company, busy = false, stack = listOf(Screen.Jobs)) }
        refreshMe()
      } catch (e: Exception) {
        _state.update { it.copy(busy = false, error = e.message ?: "Sign-in failed") }
      }
    }
  }

  fun logout() {
    app.setToken(null)
    _state.value = State()
  }

  fun refreshMe() {
    viewModelScope.launch {
      try {
        val me = app.api.me()
        _state.update { it.copy(user = me.user, company = me.company, trades = me.trades) }
        refreshJobs()
      } catch (e: ApiException.NotSignedIn) {
        logout()
      } catch (e: Exception) {
        _state.update { it.copy(error = e.message) }
      }
    }
  }

  fun refreshJobs() {
    viewModelScope.launch {
      _state.update { it.copy(loadingJobs = true) }
      try {
        val jobs = app.api.jobs()
        _state.update { it.copy(jobs = jobs, loadingJobs = false) }
      } catch (e: ApiException.NotSignedIn) {
        logout()
      } catch (e: Exception) {
        _state.update { it.copy(loadingJobs = false, error = e.message) }
      }
    }
  }

  fun createWalkIn(trade: String, title: String, complaint: String, address: String, customerName: String, customerPhone: String) {
    viewModelScope.launch {
      _state.update { it.copy(busy = true, error = null) }
      try {
        val job = app.api.createJob(trade, title, complaint, address, customerName, customerPhone)
        _state.update { it.copy(busy = false, jobs = listOf(job) + it.jobs, stack = listOf(Screen.Jobs, Screen.JobDetail(job))) }
      } catch (e: Exception) {
        _state.update { it.copy(busy = false, error = e.message) }
      }
    }
  }

  /** Starts (or resumes) a visit for [job] and opens the visit screen. */
  fun startVisit(job: Job) {
    if (visit != null) {
      push(Screen.Visit)
      return
    }
    viewModelScope.launch {
      _state.update { it.copy(busy = true, error = null) }
      try {
        val r = app.api.startVisit(job.id)
        visit = VisitController(app, r.visit, job, _state.value.company)
        _state.update { it.copy(busy = false) }
        push(Screen.Visit)
      } catch (e: Exception) {
        _state.update { it.copy(busy = false, error = e.message) }
      }
    }
  }

  /** After a visit ends: drop the controller and go to the report review. */
  fun closeVisit(openReport: Boolean) {
    val v = visit ?: return
    val id = v.visit.id
    v.dispose()
    visit = null
    _state.update { it.copy(stack = if (openReport) listOf(Screen.Jobs, Screen.Report(id)) else listOf(Screen.Jobs)) }
    refreshJobs()
  }

  override fun onCleared() {
    visit?.dispose()
    super.onCleared()
  }
}
