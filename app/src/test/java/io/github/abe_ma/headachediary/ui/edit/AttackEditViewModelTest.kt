package io.github.abe_ma.headachediary.ui.edit

import androidx.lifecycle.SavedStateHandle
import io.github.abe_ma.headachediary.data.fake.FakePressureRepository
import io.github.abe_ma.headachediary.data.fake.FakeRepositories
import io.github.abe_ma.headachediary.domain.Attack
import io.github.abe_ma.headachediary.domain.Effect
import io.github.abe_ma.headachediary.domain.Intake
import io.github.abe_ma.headachediary.domain.Medication
import io.github.abe_ma.headachediary.domain.PressurePoint
import io.github.abe_ma.headachediary.domain.Severity
import io.github.abe_ma.headachediary.ui.MainDispatcherRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Duration
import java.time.Instant

class InMemoryPromptStore(var shown: Boolean = false) : io.github.abe_ma.headachediary.ui.settings.NotificationPromptStore {
    override fun hasBeenShown() = shown
    override fun markShown() { shown = true }
}

class AttackEditViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val store = InMemoryPromptStore()
    private val med = Medication(1, "ロキソニンS", "ロキソプロフェン", isPreset = true, isHidden = false)

    private fun vm(fakes: FakeRepositories, id: Long = 0L) =
        AttackEditViewModel(fakes.attacks, fakes.settings, store, SavedStateHandle(mapOf(AttackEditViewModel.ARG_ID to id)))

    @Test
    fun FR200_新規は現在時刻_中くらい_服薬なしで始まる() {
        val s = vm(FakeRepositories()).uiState.value
        assertTrue(s.isNew)
        assertFalse(s.isLoading)
        assertEquals(Severity.MODERATE, s.severity)
        assertTrue(s.intakes.isEmpty())
        assertTrue(Duration.between(s.startedAt, Instant.now()).abs() < Duration.ofSeconds(10))
    }

    @Test
    fun FR203_FR204_薬を追加すると錠数1_未評価で入る() {
        val vm = vm(FakeRepositories())
        vm.addMedication(med)
        val i = vm.uiState.value.intakes.single()
        assertEquals(1, i.tablets)
        assertEquals(Effect.UNRATED, i.effect)
        assertEquals(med, i.medication)
    }

    @Test
    fun FR205_錠数は1以上99以下に収まる() {
        val vm = vm(FakeRepositories())
        vm.addMedication(med)
        val key = vm.uiState.value.intakes.single().key
        vm.changeTablets(key, -1)
        assertEquals(1, vm.uiState.value.intakes.single().tablets)
        vm.changeTablets(key, +1)
        assertEquals(2, vm.uiState.value.intakes.single().tablets)
        vm.changeTablets(key, +500)
        assertEquals(MAX_TABLETS, vm.uiState.value.intakes.single().tablets)
    }

    @Test
    fun FR204_効果を4段階から選べる() {
        val vm = vm(FakeRepositories())
        vm.addMedication(med)
        val key = vm.uiState.value.intakes.single().key
        Effect.entries.forEach {
            vm.setEffect(key, it)
            assertEquals(it, vm.uiState.value.intakes.single().effect)
        }
    }

    @Test
    fun FR203_服薬を削除できる() {
        val vm = vm(FakeRepositories())
        vm.addMedication(med)
        vm.addMedication(med)
        val keys = vm.uiState.value.intakes.map { it.key }
        assertEquals(2, keys.distinct().size)
        vm.removeIntake(keys[0])
        assertEquals(listOf(keys[1]), vm.uiState.value.intakes.map { it.key })
    }

    @Test
    fun FR201_保存すると記録が残り完了状態になる() = runTest {
        val fakes = FakeRepositories()
        val vm = vm(fakes)
        val at = Instant.parse("2026-11-20T03:00:00Z")
        vm.setStartedAt(at)
        vm.setSeverity(Severity.SEVERE)
        vm.addMedication(med)
        vm.setEffect(vm.uiState.value.intakes.single().key, Effect.EFFECTIVE)
        vm.save()
        val saved = fakes.attacks.observeAttacks().first().single()
        assertEquals(at, saved.startedAt)
        assertEquals(Severity.SEVERE, saved.severity)
        assertEquals(1, saved.intakes.size)
        assertTrue(saved.id != 0L)
        assertTrue(vm.uiState.value.finished)
    }

    @Test
    fun FR201_既存の記録を読み込み編集して同じidで保存できる() = runTest {
        val fakes = FakeRepositories()
        val id = fakes.attacks.save(
            Attack(0, Instant.parse("2026-11-01T00:00:00Z"), Severity.MILD, null, listOf(Intake(0, med, 2, Effect.SLIGHT))),
        )
        val vm = vm(fakes, id)
        val loaded = vm.uiState.value
        assertFalse(loaded.isNew)
        assertEquals(Severity.MILD, loaded.severity)
        assertEquals(2, loaded.intakes.single().tablets)

        vm.setSeverity(Severity.SEVERE)
        vm.save()
        val all = fakes.attacks.observeAttacks().first()
        assertEquals(1, all.size)
        assertEquals(id, all.single().id)
        assertEquals(Severity.SEVERE, all.single().severity)
    }

    @Test
    fun FR104_日時を変えずに保存すると紐づけ済みの気圧を保つ_変えると引き直す() = runTest {
        val t0 = Instant.parse("2026-11-01T00:00:00Z")
        val t1 = Instant.parse("2026-11-05T00:00:00Z")
        val pressure = FakePressureRepository(listOf(PressurePoint(t0, 1010.0), PressurePoint(t1, 995.0)))
        val fakes = FakeRepositories(pressure = pressure)
        val id = fakes.attacks.save(Attack(0, t0, Severity.MILD, null, emptyList()))
        assertEquals(1010.0, fakes.attacks.observeAttack(id).first()!!.pressureHpa)

        vm(fakes, id).apply { save() }
        assertEquals(1010.0, fakes.attacks.observeAttack(id).first()!!.pressureHpa)

        vm(fakes, id).apply { setStartedAt(t1); save() }
        assertEquals(995.0, fakes.attacks.observeAttack(id).first()!!.pressureHpa)
    }

    @Test
    fun FR200_削除すると記録が消える() = runTest {
        val fakes = FakeRepositories()
        val id = fakes.attacks.save(Attack(0, Instant.parse("2026-11-01T00:00:00Z"), Severity.MILD, null, emptyList()))
        val vm = vm(fakes, id)
        vm.delete()
        assertTrue(fakes.attacks.observeAttacks().first().isEmpty())
        assertTrue(vm.uiState.value.finished)
    }

    @Test
    fun FR200_存在しないidは見つからない状態になり保存できない() = runTest {
        val fakes = FakeRepositories()
        val vm = vm(fakes, 999L)
        assertTrue(vm.uiState.value.notFound)
        vm.save()
        assertTrue(fakes.attacks.observeAttacks().first().isEmpty())
    }

    @Test
    fun FR201_変更すると未保存扱いになり_元に戻すと解除される() {
        val vm = vm(FakeRepositories())
        assertFalse(vm.uiState.value.isDirty)
        vm.setSeverity(Severity.SEVERE)
        assertTrue(vm.uiState.value.isDirty)
        vm.setSeverity(Severity.MODERATE)
        assertFalse(vm.uiState.value.isDirty)
        vm.addMedication(med)
        assertTrue(vm.uiState.value.isDirty)
    }

    @Test
    fun FR201_プロセス死後もSavedStateHandleから編集中の内容を復元できる() {
        val fakes = FakeRepositories()
        val handle = SavedStateHandle(mapOf(AttackEditViewModel.ARG_ID to 0L))
        val first = AttackEditViewModel(fakes.attacks, fakes.settings, store, handle)
        val at = Instant.parse("2026-11-20T03:00:00Z")
        first.setStartedAt(at)
        first.setSeverity(Severity.SEVERE)
        first.addMedication(med)
        first.setEffect(first.uiState.value.intakes.single().key, Effect.EFFECTIVE)

        val restored = AttackEditViewModel(fakes.attacks, fakes.settings, store, handle).uiState.value
        assertEquals(at, restored.startedAt)
        assertEquals(Severity.SEVERE, restored.severity)
        assertEquals(med, restored.intakes.single().medication)
        assertEquals(Effect.EFFECTIVE, restored.intakes.single().effect)
        assertTrue(restored.isDirty)
    }

    @Test
    fun FR201_保存後は下書きが残らない() = runTest {
        val fakes = FakeRepositories()
        val handle = SavedStateHandle(mapOf(AttackEditViewModel.ARG_ID to 0L))
        val first = AttackEditViewModel(fakes.attacks, fakes.settings, store, handle)
        first.setSeverity(Severity.SEVERE)
        first.save()
        val again = AttackEditViewModel(fakes.attacks, fakes.settings, store, handle).uiState.value
        assertEquals(Severity.MODERATE, again.severity)
        assertFalse(again.isDirty)
    }

    @Test
    fun FR208_未来の日時は指定できず現在時刻に丸められる() {
        val vm = vm(FakeRepositories())
        vm.setStartedAt(Instant.now().plus(Duration.ofDays(3)))
        assertTrue(!vm.uiState.value.startedAt.isAfter(Instant.now()))
        val past = Instant.parse("2026-01-01T00:00:00Z")
        vm.setStartedAt(past)
        assertEquals(past, vm.uiState.value.startedAt)
    }

    private fun unratedVm(fakes: FakeRepositories): AttackEditViewModel =
        vm(fakes).also { it.addMedication(med) } // 効果は未評価で入る

    @Test
    fun FR505_未評価の服薬を初めて保存すると説明ダイアログを出し_閉じるまで完了しない() = runTest {
        val fakes = FakeRepositories()
        val vm = unratedVm(fakes)
        vm.save()
        assertEquals(PostSavePrompt.ASK, vm.uiState.value.postSavePrompt)
        assertFalse(vm.uiState.value.finished)
        assertTrue(store.shown) // 一度だけ
        assertEquals(1, fakes.attacks.observeAttacks().first().size) // 記録は保存済み
    }

    @Test
    fun FR505_受け取るで権限が得られたら効果リマインドをオンにして閉じる() = runTest {
        val fakes = FakeRepositories()
        val vm = unratedVm(fakes)
        vm.save()
        vm.onPromptAccepted(permissionGranted = true)
        assertTrue(fakes.settings.observe().first().effectReminderEnabled)
        assertTrue(vm.uiState.value.finished)
    }

    @Test
    fun FR505_権限が拒否されたらオフのまま理由を案内し_閉じると完了する() = runTest {
        val fakes = FakeRepositories()
        val vm = unratedVm(fakes)
        vm.save()
        vm.onPromptAccepted(permissionGranted = false)
        assertEquals(PostSavePrompt.DENIED, vm.uiState.value.postSavePrompt)
        assertFalse(fakes.settings.observe().first().effectReminderEnabled)
        assertFalse(vm.uiState.value.finished)
        vm.onPromptDismissed()
        assertTrue(vm.uiState.value.finished)
    }

    @Test
    fun FR505_受け取らないなら設定は変えず閉じ_次回以降は尋ねない() = runTest {
        val fakes = FakeRepositories()
        val first = unratedVm(fakes)
        first.save()
        first.onPromptDismissed()
        assertFalse(fakes.settings.observe().first().effectReminderEnabled)
        assertTrue(first.uiState.value.finished)

        val second = unratedVm(fakes)
        second.save()
        assertEquals(PostSavePrompt.NONE, second.uiState.value.postSavePrompt)
        assertTrue(second.uiState.value.finished)
    }

    @Test
    fun FR505_すでにオンの場合と未評価がない場合は尋ねない() = runTest {
        val enabled = FakeRepositories()
        enabled.settings.update { it.copy(effectReminderEnabled = true) }
        unratedVm(enabled).apply { save() }.also { assertTrue(it.uiState.value.finished) }

        val rated = FakeRepositories()
        val vm = vm(rated)
        vm.addMedication(med)
        vm.setEffect(vm.uiState.value.intakes.single().key, Effect.NONE)
        vm.save()
        assertTrue(vm.uiState.value.finished)
        assertFalse(store.shown)
    }
}
