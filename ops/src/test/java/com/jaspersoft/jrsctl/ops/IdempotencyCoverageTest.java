package com.jaspersoft.jrsctl.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsctl.core.engine.Step;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Phase 8 idempotency coverage (spec §6.1, §14): every concrete {@link Step} in the {@code ops} and
 * {@code jrs} modules must have a test that executes it twice (after a complete and, where the step
 * has intermediate states, a partial first execution) and a test that compensates it twice, each
 * asserting the end state equals a single clean execution. The table below is the executable
 * contract: {@link #COVERAGE} lists it again in code, the classpath is scanned for every class
 * implementing {@code Step}, and a new step without an entry fails this test. Non-mutating steps
 * are covered by the read-only sweeps that re-execute them at their plan position.
 *
 * <table>
 * <caption>Step to idempotency test</caption>
 * <tr><th>Step</th><th>Execute twice</th><th>Compensate twice</th></tr>
 * <tr><td>hotfix ApplySteps.VerifySignature, ValidateManifest, Preflight, RunChecks;
 *     ops.service ServiceSteps.WaitForServer</td>
 *     <td colspan="2">HotfixStepIdempotencyTest#should_not_mutate_when_read_only_apply_steps_execute_twice</td></tr>
 * <tr><td>hotfix ApplySteps.TakeSnapshot</td>
 *     <td>HotfixStepIdempotencyTest#should_converge_when_take_snapshot_executes_twice</td>
 *     <td>additive, compensation is a no-op (same test)</td></tr>
 * <tr><td>hotfix ApplySteps.StageFiles</td>
 *     <td>HotfixStepIdempotencyTest#should_converge_when_stage_files_executes_twice</td>
 *     <td>HotfixStepIdempotencyTest#should_converge_when_stage_files_compensates_twice</td></tr>
 * <tr><td>hotfix ApplySteps.AtomicSwap</td>
 *     <td>HotfixApplyTest#should_converge_when_atomic_swap_executes_twice;
 *         HotfixStepIdempotencyTest#should_converge_when_atomic_swap_executes_after_a_partial_swap</td>
 *     <td>HotfixStepIdempotencyTest#should_converge_when_atomic_swap_compensates_twice</td></tr>
 * <tr><td>hotfix ApplySteps.ApplySql</td>
 *     <td>HotfixStepIdempotencyTest#should_rerun_idempotent_scripts_when_apply_sql_executes_twice</td>
 *     <td>HotfixStepIdempotencyTest#should_converge_when_apply_sql_compensates_twice</td></tr>
 * <tr><td>hotfix ApplySteps.RecordInstalled</td>
 *     <td>HotfixStepIdempotencyTest#should_converge_when_record_installed_executes_twice</td>
 *     <td>HotfixStepIdempotencyTest#should_converge_when_record_installed_compensates_twice</td></tr>
 * <tr><td>ops.service ServiceSteps.StopService</td>
 *     <td>HotfixStepIdempotencyTest#should_stop_once_when_stop_service_executes_twice</td>
 *     <td>HotfixStepIdempotencyTest#should_start_once_when_stop_service_compensates_twice</td></tr>
 * <tr><td>ops.service ServiceSteps.StartService</td>
 *     <td>HotfixStepIdempotencyTest#should_start_once_when_start_service_executes_twice</td>
 *     <td>HotfixStepIdempotencyTest#should_stop_once_when_start_service_compensates_twice</td></tr>
 * <tr><td>hotfix RollbackSteps.RestoreSnapshot</td>
 *     <td>HotfixStepIdempotencyTest#should_converge_when_restore_snapshot_executes_twice;
 *         #should_reapply_the_hotfix_when_restore_snapshot_compensates_after_re_execution</td>
 *     <td>HotfixStepIdempotencyTest#should_converge_when_restore_snapshot_compensates_twice</td></tr>
 * <tr><td>hotfix RollbackSteps.RunSqlRollback</td>
 *     <td>HotfixStepIdempotencyTest#should_rerun_rollback_scripts_when_run_sql_rollback_executes_twice</td>
 *     <td>HotfixStepIdempotencyTest#should_converge_when_run_sql_rollback_compensates_twice</td></tr>
 * <tr><td>hotfix RollbackSteps.RecordRolledBack</td>
 *     <td>HotfixStepIdempotencyTest#should_converge_when_record_rolled_back_executes_twice</td>
 *     <td>HotfixStepIdempotencyTest#should_converge_when_record_rolled_back_compensates_twice</td></tr>
 * <tr><td>exim PreImportSnapshot</td>
 *     <td colspan="2">EximStepIdempotencyTest#should_not_mutate_when_pre_import_snapshot_executes_twice</td></tr>
 * <tr><td>exim Rephased</td>
 *     <td colspan="2">EximStepIdempotencyTest#should_delegate_unchanged_when_rephased_step_executes_twice</td></tr>
 * <tr><td>exim RestoreFromPreImportSnapshot</td>
 *     <td>read-only execute (same test)</td>
 *     <td>EximStepIdempotencyTest#should_reimport_the_snapshot_once_when_restore_from_pre_import_snapshot_compensates_twice</td></tr>
 * <tr><td>smoke SmokePlan.CreateFolder</td>
 *     <td>SmokePlanIdempotencyTest#should_create_the_folder_once_when_create_folder_executes_twice</td>
 *     <td>SmokePlanIdempotencyTest#should_delete_the_folder_once_when_create_folder_compensates_twice</td></tr>
 * <tr><td>smoke SmokePlan.UploadReport</td>
 *     <td>SmokePlanIdempotencyTest#should_upload_the_report_once_when_upload_report_executes_twice</td>
 *     <td>SmokePlanIdempotencyTest#should_delete_the_report_once_when_upload_report_compensates_twice</td></tr>
 * <tr><td>smoke SmokePlan.RunReport</td>
 *     <td colspan="2">SmokePlanIdempotencyTest#should_not_mutate_when_run_report_executes_twice</td></tr>
 * <tr><td>smoke SmokePlan.DeleteFolder (irreversible)</td>
 *     <td colspan="2">SmokePlanIdempotencyTest#should_delete_the_folder_once_when_delete_folder_executes_twice</td></tr>
 * <tr><td>upgrade PreflightSteps.Doctor, VerifyTargetPackage, ConfirmDbBackup; VerifySteps.Smoke;
 *     ServiceSteps.WaitForServer (upgrade suite)</td>
 *     <td colspan="2">UpgradeStepIdempotencyTest#should_not_mutate_when_read_only_upgrade_steps_execute_twice</td></tr>
 * <tr><td>upgrade BackupSteps.FullExport</td>
 *     <td>UpgradeStepIdempotencyTest#should_reuse_the_export_when_full_export_executes_twice</td>
 *     <td>additive, compensation keeps the backup (same test)</td></tr>
 * <tr><td>upgrade BackupSteps.BackupKeystore</td>
 *     <td>UpgradeStepIdempotencyTest#should_converge_when_backup_keystore_executes_twice</td>
 *     <td>additive (same test)</td></tr>
 * <tr><td>upgrade BackupSteps.BackupWebapp</td>
 *     <td>UpgradeStepIdempotencyTest#should_reuse_the_archives_when_backup_webapp_executes_twice</td>
 *     <td>additive (same test)</td></tr>
 * <tr><td>upgrade BackupSteps.BackupConfig</td>
 *     <td>UpgradeStepIdempotencyTest#should_converge_when_backup_config_executes_twice</td>
 *     <td>additive (same test)</td></tr>
 * <tr><td>upgrade VendorSteps.WriteMasterProperties</td>
 *     <td>UpgradeStepIdempotencyTest#should_keep_the_pristine_backup_when_write_master_properties_executes_twice;
 *         jrs MasterPropertiesTest#should_keep_pristine_backup_when_staged_twice</td>
 *     <td>UpgradeStepIdempotencyTest#should_converge_when_write_master_properties_compensates_twice;
 *         jrs MasterPropertiesTest#should_leave_the_restored_original_alone_when_restored_twice</td></tr>
 * <tr><td>upgrade VendorSteps.RunVendorUpgrade</td>
 *     <td>UpgradeStepIdempotencyTest#should_run_the_vendor_script_once_when_run_vendor_upgrade_executes_twice</td>
 *     <td>UpgradeStepIdempotencyTest#should_converge_when_run_vendor_upgrade_compensates_twice</td></tr>
 * <tr><td>ops.service ServiceSteps.StopService (upgrade suite)</td>
 *     <td>UpgradeStepIdempotencyTest#should_stop_once_when_stop_service_executes_twice</td>
 *     <td>UpgradeStepIdempotencyTest#should_start_once_when_stop_service_compensates_twice</td></tr>
 * <tr><td>ops.service ServiceSteps.StartService (upgrade suite)</td>
 *     <td>UpgradeStepIdempotencyTest#should_start_once_when_start_service_executes_twice</td>
 *     <td>UpgradeStepIdempotencyTest#should_stop_once_when_start_service_compensates_twice</td></tr>
 * <tr><td>upgrade ReconcileSteps.PlanHotfixReapply</td>
 *     <td>UpgradeStepIdempotencyTest#should_converge_when_plan_hotfix_reapply_executes_twice</td>
 *     <td>UpgradeStepIdempotencyTest#should_converge_when_plan_hotfix_reapply_compensates_twice</td></tr>
 * <tr><td>upgrade ReconcileSteps.PlanCustomizationReapply</td>
 *     <td>UpgradeStepIdempotencyTest#should_converge_when_plan_customization_reapply_executes_twice;
 *         #should_restore_every_file_when_plan_customization_reapply_compensates_after_a_partial_re_execution</td>
 *     <td>UpgradeStepIdempotencyTest#should_converge_when_plan_customization_reapply_compensates_twice</td></tr>
 * <tr><td>upgrade VerifySteps.RecordUpgrade</td>
 *     <td>UpgradeStepIdempotencyTest#should_keep_the_superseded_list_when_record_upgrade_executes_twice</td>
 *     <td>UpgradeStepIdempotencyTest#should_converge_when_record_upgrade_compensates_twice</td></tr>
 * <tr><td>upgrade EmbeddedStep</td>
 *     <td colspan="2">UpgradeStepIdempotencyTest#should_delegate_to_the_inner_step_when_embedded_step_executes_twice</td></tr>
 * <tr><td>upgrade RestoreSteps.RestoreWebapp</td>
 *     <td>UpgradeStepIdempotencyTest#should_converge_when_restore_webapp_executes_twice</td>
 *     <td>UpgradeStepIdempotencyTest#should_converge_when_restore_webapp_compensates_twice</td></tr>
 * <tr><td>upgrade RestoreSteps.RestoreBuildomatic</td>
 *     <td>UpgradeStepIdempotencyTest#should_converge_when_restore_buildomatic_executes_twice</td>
 *     <td>same code path as RestoreWebapp (RestoreDir)</td></tr>
 * <tr><td>upgrade RestoreSteps.RestoreConfig</td>
 *     <td>UpgradeStepIdempotencyTest#should_keep_the_pre_restore_snapshot_when_restore_config_executes_twice</td>
 *     <td>UpgradeStepIdempotencyTest#should_converge_when_restore_config_compensates_twice</td></tr>
 * <tr><td>upgrade RestoreSteps.RestoreKeystore</td>
 *     <td>UpgradeStepIdempotencyTest#should_keep_the_pre_restore_snapshot_when_restore_keystore_executes_twice</td>
 *     <td>same code path as RestoreConfig (RestoreFiles)</td></tr>
 * <tr><td>upgrade RestoreSteps.RecordRollback</td>
 *     <td colspan="2">UpgradeStepIdempotencyTest#should_converge_when_record_rollback_executes_twice
 *         (audit-only step)</td></tr>
 * <tr><td>jrs CheckKeystoreFingerprint, LocateVendorTools, PollExport, PollImport, VerifyImport,
 *     ServiceSteps.WaitForServer</td>
 *     <td colspan="2">jrs StepIdempotencyTest#should_mutate_nothing_when_read_only_steps_execute_twice</td></tr>
 * <tr><td>jrs StartExport</td>
 *     <td colspan="2">jrs RestStrategyExportTest#should_post_export_once_when_start_step_executes_twice;
 *         jrs StepIdempotencyTest#should_start_one_task_when_start_export_and_start_import_execute_twice</td></tr>
 * <tr><td>jrs StartImport</td>
 *     <td colspan="2">jrs RestStrategyImportTest#should_import_through_runner_and_post_once_when_start_executes_twice;
 *         jrs StepIdempotencyTest#should_start_one_task_when_start_export_and_start_import_execute_twice</td></tr>
 * <tr><td>jrs DownloadExport</td>
 *     <td>jrs StepIdempotencyTest#should_leave_one_archive_when_download_export_executes_twice_after_a_partial_write</td>
 *     <td>jrs StepIdempotencyTest#should_leave_nothing_when_download_export_compensates_twice</td></tr>
 * <tr><td>jrs WriteSidecar</td>
 *     <td>jrs StepIdempotencyTest#should_write_the_same_sidecar_when_write_sidecar_executes_twice</td>
 *     <td>jrs StepIdempotencyTest#should_leave_nothing_when_write_sidecar_compensates_twice</td></tr>
 * <tr><td>jrs RunJsExport</td>
 *     <td>jrs StepIdempotencyTest#should_replace_the_partial_archive_when_run_js_export_executes_twice</td>
 *     <td>jrs StepIdempotencyTest#should_leave_nothing_when_run_js_export_compensates_twice</td></tr>
 * <tr><td>jrs RunJsImport</td>
 *     <td colspan="2">jrs StepIdempotencyTest#should_invoke_js_import_identically_when_run_js_import_executes_twice</td></tr>
 * <tr><td>jrs ImportSourceKeystore</td>
 *     <td>jrs StepIdempotencyTest#should_back_up_the_keystore_once_when_import_source_keystore_executes_twice</td>
 *     <td>jrs StepIdempotencyTest#should_restore_the_original_keystore_when_import_source_keystore_compensates_twice</td></tr>
 * <tr><td>jrs ServiceSteps.Stop</td>
 *     <td>jrs StepIdempotencyTest#should_stop_once_when_stop_service_executes_twice</td>
 *     <td>jrs StepIdempotencyTest#should_start_once_when_stop_service_compensates_twice</td></tr>
 * <tr><td>jrs ServiceSteps.Start</td>
 *     <td>jrs StepIdempotencyTest#should_start_once_when_start_service_executes_twice</td>
 *     <td>jrs StepIdempotencyTest#should_stop_once_when_start_service_compensates_twice</td></tr>
 * </table>
 */
class IdempotencyCoverageTest {

  private static final String ROOT = "com.jaspersoft.jrsctl.";
  private static final String OPS = ROOT + "ops.";
  private static final String JRS = ROOT + "jrs.";

  private static final String H = "hotfix.HotfixStepIdempotencyTest#";
  private static final String U = "upgrade.UpgradeStepIdempotencyTest#";
  private static final String E = "exim.EximStepIdempotencyTest#";
  private static final String S = "smoke.SmokePlanIdempotencyTest#";
  private static final String J = "jrs:strategy.StepIdempotencyTest#";

  private static final String H_READ_ONLY =
      H + "should_not_mutate_when_read_only_apply_steps_execute_twice";
  private static final String U_READ_ONLY =
      U + "should_not_mutate_when_read_only_upgrade_steps_execute_twice";
  private static final String J_READ_ONLY =
      J + "should_mutate_nothing_when_read_only_steps_execute_twice";

  /**
   * Binary class name of every concrete Step to the tests that prove its idempotency. Values are
   * {@code <package below ops>.<TestClass>#<method>} for ops tests and {@code jrs:<package below
   * jrs>.<TestClass>#<method>} for jrs tests, separated by {@code ;}.
   */
  static final Map<String, String> COVERAGE =
      Map.ofEntries(
          // hotfix apply
          Map.entry(OPS + "hotfix.ApplySteps$VerifySignature", H_READ_ONLY),
          Map.entry(OPS + "hotfix.ApplySteps$ValidateManifest", H_READ_ONLY),
          Map.entry(OPS + "hotfix.ApplySteps$Preflight", H_READ_ONLY),
          Map.entry(OPS + "hotfix.ApplySteps$RunChecks", H_READ_ONLY),
          Map.entry(
              OPS + "hotfix.ApplySteps$TakeSnapshot",
              H + "should_converge_when_take_snapshot_executes_twice"),
          Map.entry(
              OPS + "hotfix.ApplySteps$StageFiles",
              H
                  + "should_converge_when_stage_files_executes_twice;"
                  + H
                  + "should_converge_when_stage_files_compensates_twice"),
          Map.entry(
              OPS + "hotfix.ApplySteps$AtomicSwap",
              "hotfix.HotfixApplyTest#should_converge_when_atomic_swap_executes_twice;"
                  + H
                  + "should_converge_when_atomic_swap_executes_after_a_partial_swap;"
                  + H
                  + "should_converge_when_atomic_swap_compensates_twice"),
          Map.entry(
              OPS + "hotfix.ApplySteps$ApplySql",
              H
                  + "should_rerun_idempotent_scripts_when_apply_sql_executes_twice;"
                  + H
                  + "should_converge_when_apply_sql_compensates_twice"),
          Map.entry(
              OPS + "hotfix.ApplySteps$RecordInstalled",
              H
                  + "should_converge_when_record_installed_executes_twice;"
                  + H
                  + "should_converge_when_record_installed_compensates_twice"),
          // the service steps shared by every plan (ops.service.ServiceSteps): both suites cover
          // them
          Map.entry(
              OPS + "service.ServiceSteps$StopService",
              H
                  + "should_stop_once_when_stop_service_executes_twice;"
                  + H
                  + "should_start_once_when_stop_service_compensates_twice;"
                  + U
                  + "should_stop_once_when_stop_service_executes_twice;"
                  + U
                  + "should_start_once_when_stop_service_compensates_twice"),
          Map.entry(
              OPS + "service.ServiceSteps$StartService",
              H
                  + "should_start_once_when_start_service_executes_twice;"
                  + H
                  + "should_stop_once_when_start_service_compensates_twice;"
                  + U
                  + "should_start_once_when_start_service_executes_twice;"
                  + U
                  + "should_stop_once_when_start_service_compensates_twice"),
          Map.entry(OPS + "service.ServiceSteps$WaitForServer", H_READ_ONLY + ";" + U_READ_ONLY),
          // hotfix rollback
          Map.entry(
              OPS + "hotfix.RollbackSteps$RestoreSnapshot",
              H
                  + "should_converge_when_restore_snapshot_executes_twice;"
                  + H
                  + "should_reapply_the_hotfix_when_restore_snapshot_compensates_after_re_execution;"
                  + H
                  + "should_converge_when_restore_snapshot_compensates_twice"),
          Map.entry(
              OPS + "hotfix.RollbackSteps$RunSqlRollback",
              H
                  + "should_rerun_rollback_scripts_when_run_sql_rollback_executes_twice;"
                  + H
                  + "should_converge_when_run_sql_rollback_compensates_twice"),
          Map.entry(
              OPS + "hotfix.RollbackSteps$RecordRolledBack",
              H
                  + "should_converge_when_record_rolled_back_executes_twice;"
                  + H
                  + "should_converge_when_record_rolled_back_compensates_twice"),
          // export/import
          Map.entry(
              OPS + "exim.PreImportSnapshot",
              E + "should_not_mutate_when_pre_import_snapshot_executes_twice"),
          Map.entry(
              OPS + "exim.Rephased",
              E + "should_delegate_unchanged_when_rephased_step_executes_twice"),
          Map.entry(
              OPS + "exim.RestoreFromPreImportSnapshot",
              E
                  + "should_reimport_the_snapshot_once_when_restore_from_pre_import_snapshot_compensates_twice"),
          // smoke
          Map.entry(
              OPS + "smoke.SmokePlan$CreateFolder",
              S
                  + "should_create_the_folder_once_when_create_folder_executes_twice;"
                  + S
                  + "should_delete_the_folder_once_when_create_folder_compensates_twice"),
          Map.entry(
              OPS + "smoke.SmokePlan$UploadReport",
              S
                  + "should_upload_the_report_once_when_upload_report_executes_twice;"
                  + S
                  + "should_delete_the_report_once_when_upload_report_compensates_twice"),
          Map.entry(
              OPS + "smoke.SmokePlan$RunReport",
              S + "should_not_mutate_when_run_report_executes_twice"),
          Map.entry(
              OPS + "smoke.SmokePlan$DeleteFolder",
              S + "should_delete_the_folder_once_when_delete_folder_executes_twice"),
          // upgrade
          Map.entry(OPS + "upgrade.PreflightSteps$Doctor", U_READ_ONLY),
          Map.entry(OPS + "upgrade.PreflightSteps$VerifyTargetPackage", U_READ_ONLY),
          Map.entry(OPS + "upgrade.PreflightSteps$ConfirmDbBackup", U_READ_ONLY),
          Map.entry(
              OPS + "upgrade.BackupSteps$FullExport",
              U + "should_reuse_the_export_when_full_export_executes_twice"),
          Map.entry(
              OPS + "upgrade.BackupSteps$BackupKeystore",
              U + "should_converge_when_backup_keystore_executes_twice"),
          Map.entry(
              OPS + "upgrade.BackupSteps$BackupWebapp",
              U + "should_reuse_the_archives_when_backup_webapp_executes_twice"),
          Map.entry(
              OPS + "upgrade.BackupSteps$BackupConfig",
              U + "should_converge_when_backup_config_executes_twice"),
          Map.entry(
              OPS + "upgrade.VendorSteps$WriteMasterProperties",
              U
                  + "should_keep_the_pristine_backup_when_write_master_properties_executes_twice;"
                  + U
                  + "should_converge_when_write_master_properties_compensates_twice;"
                  + "jrs:vendor.MasterPropertiesTest#should_keep_pristine_backup_when_staged_twice;"
                  + "jrs:vendor.MasterPropertiesTest#should_leave_the_restored_original_alone_when_restored_twice"),
          Map.entry(
              OPS + "upgrade.VendorSteps$RunVendorUpgrade",
              U
                  + "should_run_the_vendor_script_once_when_run_vendor_upgrade_executes_twice;"
                  + U
                  + "should_converge_when_run_vendor_upgrade_compensates_twice"),
          Map.entry(
              OPS + "upgrade.ReconcileSteps$PlanHotfixReapply",
              U
                  + "should_converge_when_plan_hotfix_reapply_executes_twice;"
                  + U
                  + "should_converge_when_plan_hotfix_reapply_compensates_twice"),
          Map.entry(
              OPS + "upgrade.ReconcileSteps$PlanCustomizationReapply",
              U
                  + "should_converge_when_plan_customization_reapply_executes_twice;"
                  + U
                  + "should_restore_every_file_when_plan_customization_reapply_compensates_after_a_partial_re_execution;"
                  + U
                  + "should_converge_when_plan_customization_reapply_compensates_twice"),
          Map.entry(OPS + "upgrade.VerifySteps$Smoke", U_READ_ONLY),
          Map.entry(
              OPS + "upgrade.VerifySteps$RecordUpgrade",
              U
                  + "should_keep_the_superseded_list_when_record_upgrade_executes_twice;"
                  + U
                  + "should_converge_when_record_upgrade_compensates_twice"),
          Map.entry(
              OPS + "upgrade.EmbeddedStep",
              U + "should_delegate_to_the_inner_step_when_embedded_step_executes_twice"),
          Map.entry(
              OPS + "upgrade.RestoreSteps$RestoreWebapp",
              U
                  + "should_converge_when_restore_webapp_executes_twice;"
                  + U
                  + "should_converge_when_restore_webapp_compensates_twice"),
          Map.entry(
              OPS + "upgrade.RestoreSteps$RestoreBuildomatic",
              U + "should_converge_when_restore_buildomatic_executes_twice"),
          Map.entry(
              OPS + "upgrade.RestoreSteps$RestoreConfig",
              U
                  + "should_keep_the_pre_restore_snapshot_when_restore_config_executes_twice;"
                  + U
                  + "should_converge_when_restore_config_compensates_twice"),
          Map.entry(
              OPS + "upgrade.RestoreSteps$RestoreKeystore",
              U + "should_keep_the_pre_restore_snapshot_when_restore_keystore_executes_twice"),
          Map.entry(
              OPS + "upgrade.RestoreSteps$RecordRollback",
              U + "should_converge_when_record_rollback_executes_twice"),
          // jrs strategies
          Map.entry(JRS + "strategy.CheckKeystoreFingerprint", J_READ_ONLY),
          Map.entry(JRS + "strategy.LocateVendorTools", J_READ_ONLY),
          Map.entry(JRS + "strategy.PollExport", J_READ_ONLY),
          Map.entry(JRS + "strategy.PollImport", J_READ_ONLY),
          Map.entry(JRS + "strategy.VerifyImport", J_READ_ONLY),
          Map.entry(JRS + "strategy.ServiceSteps$WaitForServer", J_READ_ONLY),
          Map.entry(
              JRS + "strategy.StartExport",
              "jrs:strategy.RestStrategyExportTest#should_post_export_once_when_start_step_executes_twice;"
                  + J
                  + "should_start_one_task_when_start_export_and_start_import_execute_twice"),
          Map.entry(
              JRS + "strategy.StartImport",
              "jrs:strategy.RestStrategyImportTest#should_import_through_runner_and_post_once_when_start_executes_twice;"
                  + J
                  + "should_start_one_task_when_start_export_and_start_import_execute_twice"),
          Map.entry(
              JRS + "strategy.DownloadExport",
              J
                  + "should_leave_one_archive_when_download_export_executes_twice_after_a_partial_write;"
                  + J
                  + "should_leave_nothing_when_download_export_compensates_twice"),
          Map.entry(
              JRS + "strategy.WriteSidecar",
              J
                  + "should_write_the_same_sidecar_when_write_sidecar_executes_twice;"
                  + J
                  + "should_leave_nothing_when_write_sidecar_compensates_twice"),
          Map.entry(
              JRS + "strategy.RunJsExport",
              J
                  + "should_replace_the_partial_archive_when_run_js_export_executes_twice;"
                  + J
                  + "should_leave_nothing_when_run_js_export_compensates_twice"),
          Map.entry(
              JRS + "strategy.RunJsImport",
              J + "should_invoke_js_import_identically_when_run_js_import_executes_twice"),
          Map.entry(
              JRS + "strategy.ImportSourceKeystore",
              J
                  + "should_back_up_the_keystore_once_when_import_source_keystore_executes_twice;"
                  + J
                  + "should_restore_the_original_keystore_when_import_source_keystore_compensates_twice"),
          Map.entry(
              JRS + "strategy.ServiceSteps$Stop",
              J
                  + "should_stop_once_when_stop_service_executes_twice;"
                  + J
                  + "should_start_once_when_stop_service_compensates_twice"),
          Map.entry(
              JRS + "strategy.ServiceSteps$Start",
              J
                  + "should_start_once_when_start_service_executes_twice;"
                  + J
                  + "should_stop_once_when_start_service_compensates_twice"));

  @Test
  void should_list_every_concrete_step_of_ops_and_jrs_in_the_coverage_table() throws Exception {
    TreeSet<String> steps = new TreeSet<>();
    steps.addAll(concreteSteps("ops"));
    steps.addAll(concreteSteps("jrs"));

    // 56 since the hotfix and upgrade service-step families became one (ops.service.ServiceSteps)
    assertThat(steps).as("classpath scan found the known steps").hasSizeGreaterThanOrEqualTo(56);
    assertThat(steps)
        .as("every Step implementation needs an idempotency test (add it to COVERAGE)")
        .allSatisfy(step -> assertThat(COVERAGE).containsKey(step));
    assertThat(new TreeSet<>(COVERAGE.keySet()))
        .as("stale table entries: classes that no longer exist or are not Steps")
        .isSubsetOf(steps);
  }

  @Test
  void should_name_existing_test_methods_in_every_coverage_entry() throws Exception {
    Map<String, String> missing = new TreeMap<>();
    for (Map.Entry<String, String> e : COVERAGE.entrySet()) {
      for (String ref : List.of(e.getValue().split(";", -1))) {
        if (!testExists(ref)) {
          missing.put(e.getKey(), ref);
        }
      }
    }
    assertThat(missing).as("coverage entries naming tests that do not exist").isEmpty();
  }

  // ---------------------------------------------------------------- classpath scan

  /**
   * Binary names of every non-abstract class implementing Step below {@code
   * com.jaspersoft.jrsctl.<module>}.
   */
  private static List<String> concreteSteps(String module) throws Exception {
    ClassLoader loader = Step.class.getClassLoader();
    String prefix = "com/jaspersoft/jrsctl/" + module;
    List<String> names = new ArrayList<>();
    Enumeration<URL> roots = loader.getResources(prefix);
    while (roots.hasMoreElements()) {
      URL url = roots.nextElement();
      if (url.getPath().contains("test-classes")) {
        continue;
      }
      switch (url.getProtocol()) {
        case "file" -> names.addAll(classesUnder(Path.of(url.toURI()), prefix));
        case "jar" -> names.addAll(classesInJar(url, prefix));
        default -> throw new AssertionError("unexpected classpath entry " + url);
      }
    }
    List<String> steps = new ArrayList<>();
    for (String name : names) {
      Class<?> c = Class.forName(name, false, loader);
      if (Step.class.isAssignableFrom(c)
          && !c.isInterface()
          && !Modifier.isAbstract(c.getModifiers())) {
        steps.add(name);
      }
    }
    return steps;
  }

  private static List<String> classesUnder(Path dir, String prefix) throws IOException {
    List<String> names = new ArrayList<>();
    try (Stream<Path> walk = Files.walk(dir)) {
      for (Path p : walk.filter(p -> p.toString().endsWith(".class")).toList()) {
        String rel = dir.relativize(p).toString().replace('\\', '/');
        names.add(toClassName(prefix + "/" + rel));
      }
    }
    return names;
  }

  private static List<String> classesInJar(URL url, String prefix) throws IOException {
    List<String> names = new ArrayList<>();
    JarURLConnection connection = (JarURLConnection) url.openConnection();
    try (JarFile jar = connection.getJarFile()) {
      Enumeration<JarEntry> entries = jar.entries();
      while (entries.hasMoreElements()) {
        String entry = entries.nextElement().getName();
        if (entry.startsWith(prefix + "/") && entry.endsWith(".class")) {
          names.add(toClassName(entry));
        }
      }
    }
    return names;
  }

  private static String toClassName(String resource) {
    return resource.substring(0, resource.length() - ".class".length()).replace('/', '.');
  }

  // ---------------------------------------------------------------- test references

  private static boolean testExists(String ref) throws Exception {
    int hash = ref.indexOf('#');
    String cls = ref.substring(0, hash);
    String method = ref.substring(hash + 1);
    if (cls.startsWith("jrs:")) {
      return jrsTestSourceHas(cls.substring("jrs:".length()), method);
    }
    Class<?> test = Class.forName(OPS + cls);
    for (Method m : test.getDeclaredMethods()) {
      if (m.getName().equals(method)) {
        return true;
      }
    }
    return false;
  }

  /** The jrs tests are not on this module's classpath; their sources are checked instead. */
  private static boolean jrsTestSourceHas(String cls, String method) throws IOException {
    Path source =
        jrsTestSources()
            .resolve("com/jaspersoft/jrsctl/jrs/" + cls.replace('.', '/') + ".java")
            .normalize();
    assertThat(source).as("jrs test source for " + cls).isRegularFile();
    return Files.readString(source, StandardCharsets.UTF_8).contains("void " + method + "(");
  }

  private static Path jrsTestSources() {
    Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    for (int up = 0; up < 4 && dir != null; up++, dir = dir.getParent()) {
      Path candidate = dir.resolve("jrs").resolve("src").resolve("test").resolve("java");
      if (Files.isDirectory(candidate)) {
        return candidate;
      }
    }
    throw new AssertionError(
        "cannot locate jrs/src/test/java from "
            + Optional.ofNullable(System.getProperty("user.dir")));
  }
}
