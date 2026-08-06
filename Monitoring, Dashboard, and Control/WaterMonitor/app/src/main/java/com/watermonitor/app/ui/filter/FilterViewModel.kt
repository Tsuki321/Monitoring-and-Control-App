package com.watermonitor.app.ui.filter

import androidx.lifecycle.ViewModel
import com.watermonitor.app.data.model.FilterHealthState
import com.watermonitor.app.data.repository.FilterHealthRepository
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin pass-through over [FilterHealthRepository].
 *
 * The repository already owns the state and publishes a hot `StateFlow`, so there is nothing
 * to combine or transform here — the ViewModel exists to keep the Fragment from reaching
 * into the data layer directly and to give service actions a single entry point.
 */
class FilterViewModel : ViewModel() {

    val filterHealth: StateFlow<FilterHealthState> = FilterHealthRepository.filterHealthFlow

    fun rinseStage(stageIndex: Int) = FilterHealthRepository.markRinsed(stageIndex)

    fun replaceStage(stageIndex: Int) = FilterHealthRepository.markReplaced(stageIndex)

    fun resetHistory() = FilterHealthRepository.resetAll()
}
