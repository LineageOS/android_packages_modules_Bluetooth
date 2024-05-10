/*
 *  Copyright (c) 2022 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at:
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

#include <bluetooth/log.h>

#include <mutex>
#include <string>
#include <string_view>

#include "audio_hal_client/audio_hal_client.h"
#include "audio_set_configurations_generated.h"
#include "audio_set_scenarios_generated.h"
#include "btm_iso_api_types.h"
#include "flatbuffers/idl.h"
#include "flatbuffers/util.h"
#include "le_audio/le_audio_types.h"
#include "le_audio_set_configuration_provider.h"
#include "os/log.h"
#include "osi/include/osi.h"
#include "osi/include/properties.h"

using bluetooth::le_audio::set_configurations::AseConfiguration;
using bluetooth::le_audio::set_configurations::AudioSetConfiguration;
using bluetooth::le_audio::set_configurations::AudioSetConfigurations;
using bluetooth::le_audio::set_configurations::CodecConfigSetting;
using bluetooth::le_audio::set_configurations::LeAudioCodecIdLc3;
using bluetooth::le_audio::set_configurations::QosConfigSetting;
using bluetooth::le_audio::types::LeAudioContextType;

namespace bluetooth::le_audio {

#ifdef __ANDROID__
static const std::vector<
    std::pair<const char* /*schema*/, const char* /*content*/>>
    kLeAudioSetConfigs = {
        {"/apex/com.android.btservices/etc/bluetooth/le_audio/"
         "audio_set_configurations.bfbs",
         "/apex/com.android.btservices/etc/bluetooth/le_audio/"
         "audio_set_configurations.json"}};
static const std::vector<
    std::pair<const char* /*schema*/, const char* /*content*/>>
    kLeAudioSetScenarios = {{"/apex/com.android.btservices/etc/bluetooth/"
                             "le_audio/audio_set_scenarios.bfbs",
                             "/apex/com.android.btservices/etc/bluetooth/"
                             "le_audio/audio_set_scenarios.json"}};
#elif defined(TARGET_FLOSS)
static const std::vector<
    std::pair<const char* /*schema*/, const char* /*content*/>>
    kLeAudioSetConfigs = {
        {"/etc/bluetooth/le_audio/audio_set_configurations.bfbs",
         "/etc/bluetooth/le_audio/audio_set_configurations.json"}};
static const std::vector<
    std::pair<const char* /*schema*/, const char* /*content*/>>
    kLeAudioSetScenarios = {
        {"/etc/bluetooth/le_audio/audio_set_scenarios.bfbs",
         "/etc/bluetooth/le_audio/audio_set_scenarios.json"}};
#else
static const std::vector<
    std::pair<const char* /*schema*/, const char* /*content*/>>
    kLeAudioSetConfigs = {
        {"audio_set_configurations.bfbs", "audio_set_configurations.json"}};
static const std::vector<
    std::pair<const char* /*schema*/, const char* /*content*/>>
    kLeAudioSetScenarios = {
        {"audio_set_scenarios.bfbs", "audio_set_scenarios.json"}};
#endif

/** Provides a set configurations for the given context type */
struct AudioSetConfigurationProviderJson {
  static constexpr auto kDefaultScenario = "Media";

  AudioSetConfigurationProviderJson(types::CodecLocation location) {
    log::assert_that(
        LoadContent(kLeAudioSetConfigs, kLeAudioSetScenarios, location),
        ": Unable to load le audio set configuration files.");
  }

  /* Use the same scenario configurations for different contexts to avoid
   * internal reconfiguration and handover that produces time gap. When using
   * the same scenario for different contexts, quality and configuration remains
   * the same while changing to same scenario based context type.
   */
  static auto ScenarioToContextTypes(const std::string& scenario) {
    static const std::multimap<std::string,
                               ::bluetooth::le_audio::types::LeAudioContextType>
        scenarios = {
            {"Media", types::LeAudioContextType::ALERTS},
            {"Media", types::LeAudioContextType::INSTRUCTIONAL},
            {"Media", types::LeAudioContextType::NOTIFICATIONS},
            {"Media", types::LeAudioContextType::EMERGENCYALARM},
            {"Media", types::LeAudioContextType::UNSPECIFIED},
            {"Media", types::LeAudioContextType::MEDIA},
            {"Conversational", types::LeAudioContextType::RINGTONE},
            {"Conversational", types::LeAudioContextType::CONVERSATIONAL},
            {"Live", types::LeAudioContextType::LIVE},
            {"Game", types::LeAudioContextType::GAME},
            {"VoiceAssistants", types::LeAudioContextType::VOICEASSISTANTS},
        };
    return scenarios.equal_range(scenario);
  }

  static std::string ContextTypeToScenario(
      ::bluetooth::le_audio::types::LeAudioContextType context_type) {
    switch (context_type) {
      case types::LeAudioContextType::ALERTS:
        FALLTHROUGH_INTENDED;
      case types::LeAudioContextType::INSTRUCTIONAL:
        FALLTHROUGH_INTENDED;
      case types::LeAudioContextType::NOTIFICATIONS:
        FALLTHROUGH_INTENDED;
      case types::LeAudioContextType::EMERGENCYALARM:
        FALLTHROUGH_INTENDED;
      case types::LeAudioContextType::UNSPECIFIED:
        FALLTHROUGH_INTENDED;
      case types::LeAudioContextType::SOUNDEFFECTS:
        FALLTHROUGH_INTENDED;
      case types::LeAudioContextType::MEDIA:
        return "Media";
      case types::LeAudioContextType::RINGTONE:
        FALLTHROUGH_INTENDED;
      case types::LeAudioContextType::CONVERSATIONAL:
        return "Conversational";
      case types::LeAudioContextType::LIVE:
        return "Live";
      case types::LeAudioContextType::GAME:
        return "Game";
      case types::LeAudioContextType::VOICEASSISTANTS:
        return "VoiceAssistants";
      default:
        return kDefaultScenario;
    }
  }

  const AudioSetConfigurations* GetConfigurationsByContextType(
      LeAudioContextType context_type) const {
    if (context_configurations_.count(context_type))
      return &context_configurations_.at(context_type);

    log::warn(": No predefined scenario for the context {} was found.",
              (int)context_type);

    auto [it_begin, it_end] = ScenarioToContextTypes(kDefaultScenario);
    if (it_begin != it_end) {
      log::warn(": Using '{}' scenario by default.", kDefaultScenario);
      return &context_configurations_.at(it_begin->second);
    }

    log::error(
        ": No valid configuration for the default '{}' scenario, or no audio "
        "set configurations loaded at all.",
        kDefaultScenario);
    return nullptr;
  };

 private:
  /* Codec configurations */
  std::map<std::string, const AudioSetConfiguration> configurations_;

  /* Maps of context types to a set of configuration structs */
  std::map<::bluetooth::le_audio::types::LeAudioContextType,
           AudioSetConfigurations>
      context_configurations_;

  static CodecConfigSetting CodecConfigSettingFromFlat(
      const fbs::le_audio::CodecId* flat_codec_id,
      const flatbuffers::Vector<
          flatbuffers::Offset<fbs::le_audio::CodecSpecificConfiguration>>*
          flat_codec_specific_params) {
    CodecConfigSetting codec;

    /* Cache the bluetooth::le_audio::types::CodecId type value */
    codec.id = types::LeAudioCodecId({
        .coding_format = flat_codec_id->coding_format(),
        .vendor_company_id = flat_codec_id->vendor_company_id(),
        .vendor_codec_id = flat_codec_id->vendor_codec_id(),
    });

    /* Cache all the codec specific parameters */
    for (auto const& param : *flat_codec_specific_params) {
      auto const value = param->compound_value()->value();
      codec.params.Add(
          param->type(),
          std::vector<uint8_t>(value->data(), value->data() + value->size()));
    }
    return codec;
  }

  void SetConfigurationFromFlatSubconfig(
      const fbs::le_audio::AudioSetSubConfiguration* flat_subconfig,
      QosConfigSetting qos, std::vector<AseConfiguration>& subconfigs,
      types::CodecLocation location) {
    auto codec_config = CodecConfigSettingFromFlat(
        flat_subconfig->codec_id(), flat_subconfig->codec_configuration());

    // Fill in the remaining params
    codec_config.channel_count_per_iso_stream =
        flat_subconfig->ase_channel_cnt();

    auto config = AseConfiguration(codec_config, qos);

    // Note that these parameters are set here since for now, we are using the
    // common configuration source for all the codec locations.
    switch (location) {
      case types::CodecLocation::ADSP:
        config.data_path_configuration.dataPathId =
            bluetooth::hci::iso_manager::kIsoDataPathPlatformDefault;
        config.data_path_configuration.dataPathConfig = {};
        config.data_path_configuration.isoDataPathConfig.isTransparent = true;
        config.data_path_configuration.isoDataPathConfig.controllerDelayUs = 0;
        config.data_path_configuration.isoDataPathConfig.codecId = {
            .coding_format = bluetooth::hci::kIsoCodingFormatTransparent,
            .vendor_company_id = 0,
            .vendor_codec_id = 0};
        config.data_path_configuration.isoDataPathConfig.configuration = {};
        break;
      case types::CodecLocation::HOST:
        config.data_path_configuration.dataPathId =
            bluetooth::hci::iso_manager::kIsoDataPathHci;
        config.data_path_configuration.dataPathConfig = {};
        config.data_path_configuration.isoDataPathConfig.isTransparent = true;
        config.data_path_configuration.isoDataPathConfig.codecId = {
            .coding_format = bluetooth::hci::kIsoCodingFormatTransparent,
            .vendor_company_id = 0,
            .vendor_codec_id = 0};
        config.data_path_configuration.isoDataPathConfig.controllerDelayUs = 0;
        config.data_path_configuration.isoDataPathConfig.configuration = {};
        break;
      case types::CodecLocation::CONTROLLER:
        config.data_path_configuration.dataPathId =
            bluetooth::hci::iso_manager::kIsoDataPathPlatformDefault;
        config.data_path_configuration.dataPathConfig = {};
        config.data_path_configuration.isoDataPathConfig.isTransparent = false;
        // Note: The data path codecId matches the used codec, but for now there
        //       is no support for the custom path configuration data buffer.
        config.data_path_configuration.isoDataPathConfig.codecId =
            codec_config.id;
        config.data_path_configuration.isoDataPathConfig.controllerDelayUs = 0;
        config.data_path_configuration.isoDataPathConfig.configuration = {};
        break;
    }

    // Store each ASE configuration
    for (auto i = flat_subconfig->ase_cnt(); i; --i) {
      subconfigs.push_back(std::move(config));
    }
  }

  static uint8_t ValidateTargetLatency(int flat_target_latency) {
    auto target_latency_int = static_cast<int>(flat_target_latency);

    bool valid_target_latency =
        (target_latency_int >= (int)types::kTargetLatencyLower &&
         target_latency_int <= (int)types::kTargetLatencyHigherReliability);

    return valid_target_latency
               ? static_cast<uint8_t>(target_latency_int)
               : types::kTargetLatencyBalancedLatencyReliability;
  }

  AudioSetConfiguration AudioSetConfigurationFromFlat(
      const fbs::le_audio::AudioSetConfiguration* flat_cfg,
      std::vector<const fbs::le_audio::CodecConfiguration*>* codec_cfgs,
      std::vector<const fbs::le_audio::QosConfiguration*>* qos_cfgs,
      types::CodecLocation location) {
    log::assert_that(flat_cfg != nullptr, "flat_cfg cannot be null");
    std::string codec_config_key = flat_cfg->codec_config_name()->str();
    auto* qos_config_key_array = flat_cfg->qos_config_name();

    constexpr std::string_view default_qos = "QoS_Config_Balanced_Reliability";

    std::string qos_sink_key(default_qos);
    std::string qos_source_key(default_qos);

    /* We expect maximum two QoS settings. First for Sink and second for Source
     */
    if (qos_config_key_array->size() > 0) {
      qos_sink_key = qos_config_key_array->Get(0)->str();
      if (qos_config_key_array->size() > 1) {
        qos_source_key = qos_config_key_array->Get(1)->str();
      } else {
        qos_source_key = qos_sink_key;
      }
    }

    log::info(
        "Audio set config {}: codec config {}, qos_sink {}, qos_source {}",
        flat_cfg->name()->c_str(), codec_config_key, qos_sink_key,
        qos_source_key);

    const fbs::le_audio::QosConfiguration* qos_sink_cfg = nullptr;
    for (auto i = qos_cfgs->begin(); i != qos_cfgs->end(); ++i) {
      if ((*i)->name()->str() == qos_sink_key) {
        qos_sink_cfg = *i;
        break;
      }
    }

    const fbs::le_audio::QosConfiguration* qos_source_cfg = nullptr;
    for (auto i = qos_cfgs->begin(); i != qos_cfgs->end(); ++i) {
      if ((*i)->name()->str() == qos_source_key) {
        qos_source_cfg = *i;
        break;
      }
    }

    types::BidirectionalPair<QosConfigSetting> qos;

    if (qos_sink_cfg != nullptr) {
      qos.sink.target_latency =
          ValidateTargetLatency(qos_sink_cfg->target_latency());
      qos.sink.retransmission_number = qos_sink_cfg->retransmission_number();
      qos.sink.max_transport_latency = qos_sink_cfg->max_transport_latency();
    } else {
      log::error("No qos config matching key {} found", qos_sink_key);
    }

    if (qos_source_cfg != nullptr) {
      qos.source.target_latency =
          ValidateTargetLatency(qos_source_cfg->target_latency());
      qos.source.retransmission_number =
          qos_source_cfg->retransmission_number();
      qos.source.max_transport_latency =
          qos_source_cfg->max_transport_latency();
    } else {
      log::error("No qos config matching key {} found", qos_source_key);
    }

    const fbs::le_audio::CodecConfiguration* codec_cfg = nullptr;
    for (auto i = codec_cfgs->begin(); i != codec_cfgs->end(); ++i) {
      if ((*i)->name()->str() == codec_config_key) {
        codec_cfg = *i;
        break;
      }
    }

    types::BidirectionalPair<std::vector<AseConfiguration>> subconfigs;
    if (codec_cfg != nullptr && codec_cfg->subconfigurations()) {
      /* Load subconfigurations */
      for (auto subconfig : *codec_cfg->subconfigurations()) {
        auto direction = subconfig->direction();
        processSubconfig(*subconfig, qos.get(direction),
                         subconfigs.get(direction), location);
      }
    } else {
      if (codec_cfg == nullptr) {
        log::error("No codec config matching key {} found", codec_config_key);
      } else {
        log::error("Configuration '{}' has no valid subconfigurations.",
                   flat_cfg->name()->c_str());
      }
    }

    return {
        .name = flat_cfg->name()->c_str(),
        .packing = bluetooth::hci::kIsoCigPackingSequential,
        .confs = std::move(subconfigs),
    };
  }

  void processSubconfig(
      const fbs::le_audio::AudioSetSubConfiguration& subconfig,
      const QosConfigSetting& qos_setting,
      std::vector<AseConfiguration>& subconfigs,
      types::CodecLocation location) {
    SetConfigurationFromFlatSubconfig(&subconfig, qos_setting, subconfigs,
                                      location);

    // Recalculate some qos params based on the Core Codec Configuration
    for (auto& subconfig : subconfigs) {
      const auto& core_config = subconfig.codec.params.GetAsCoreCodecConfig();
      subconfig.qos.maxSdu =
          subconfig.codec.GetChannelCountPerIsoStream() *
          core_config.octets_per_codec_frame.value_or(0) *
          core_config.codec_frames_blocks_per_sdu.value_or(1);
      subconfig.qos.sduIntervalUs = core_config.GetFrameDurationUs();
    }
  }

  bool LoadConfigurationsFromFiles(const char* schema_file,
                                   const char* content_file,
                                   types::CodecLocation location) {
    flatbuffers::Parser configurations_parser_;
    std::string configurations_schema_binary_content;
    bool ok = flatbuffers::LoadFile(schema_file, true,
                                    &configurations_schema_binary_content);
    if (!ok) return ok;

    /* Load the binary schema */
    ok = configurations_parser_.Deserialize(
        (uint8_t*)configurations_schema_binary_content.c_str(),
        configurations_schema_binary_content.length());
    if (!ok) return ok;

    /* Load the content from JSON */
    std::string configurations_json_content;
    ok = flatbuffers::LoadFile(content_file, false,
                               &configurations_json_content);
    if (!ok) return ok;

    /* Parse */
    ok = configurations_parser_.Parse(configurations_json_content.c_str());
    if (!ok) return ok;

    /* Import from flatbuffers */
    auto configurations_root = fbs::le_audio::GetAudioSetConfigurations(
        configurations_parser_.builder_.GetBufferPointer());
    if (!configurations_root) return false;

    auto flat_qos_configs = configurations_root->qos_configurations();
    if ((flat_qos_configs == nullptr) || (flat_qos_configs->size() == 0))
      return false;

    log::debug(": Updating {} qos config entries.", flat_qos_configs->size());
    std::vector<const fbs::le_audio::QosConfiguration*> qos_cfgs;
    for (auto const& flat_qos_cfg : *flat_qos_configs) {
      qos_cfgs.push_back(flat_qos_cfg);
    }

    auto flat_codec_configs = configurations_root->codec_configurations();
    if ((flat_codec_configs == nullptr) || (flat_codec_configs->size() == 0))
      return false;

    log::debug(": Updating {} codec config entries.",
               flat_codec_configs->size());
    std::vector<const fbs::le_audio::CodecConfiguration*> codec_cfgs;
    for (auto const& flat_codec_cfg : *flat_codec_configs) {
      codec_cfgs.push_back(flat_codec_cfg);
    }

    auto flat_configs = configurations_root->configurations();
    if ((flat_configs == nullptr) || (flat_configs->size() == 0)) return false;

    log::debug(": Updating {} config entries.", flat_configs->size());
    for (auto const& flat_cfg : *flat_configs) {
      auto configuration = AudioSetConfigurationFromFlat(flat_cfg, &codec_cfgs,
                                                         &qos_cfgs, location);
      if (!configuration.confs.sink.empty() ||
          !configuration.confs.source.empty()) {
        configurations_.insert({flat_cfg->name()->str(), configuration});
      }
    }

    return true;
  }

  AudioSetConfigurations AudioSetConfigurationsFromFlatScenario(
      const fbs::le_audio::AudioSetScenario* const flat_scenario) {
    AudioSetConfigurations items;
    if (!flat_scenario->configurations()) return items;

    for (auto config_name : *flat_scenario->configurations()) {
      if (configurations_.count(config_name->str()) == 0) continue;

      auto& cfg = configurations_.at(config_name->str());
      items.push_back(&cfg);
    }

    return items;
  }

  bool LoadScenariosFromFiles(const char* schema_file,
                              const char* content_file) {
    flatbuffers::Parser scenarios_parser_;
    std::string scenarios_schema_binary_content;
    bool ok = flatbuffers::LoadFile(schema_file, true,
                                    &scenarios_schema_binary_content);
    if (!ok) return ok;

    /* Load the binary schema */
    ok = scenarios_parser_.Deserialize(
        (uint8_t*)scenarios_schema_binary_content.c_str(),
        scenarios_schema_binary_content.length());
    if (!ok) return ok;

    /* Load the content from JSON */
    std::string scenarios_json_content;
    ok = flatbuffers::LoadFile(content_file, false, &scenarios_json_content);
    if (!ok) return ok;

    /* Parse */
    ok = scenarios_parser_.Parse(scenarios_json_content.c_str());
    if (!ok) return ok;

    /* Import from flatbuffers */
    auto scenarios_root = fbs::le_audio::GetAudioSetScenarios(
        scenarios_parser_.builder_.GetBufferPointer());
    if (!scenarios_root) return false;

    auto flat_scenarios = scenarios_root->scenarios();
    if ((flat_scenarios == nullptr) || (flat_scenarios->size() == 0))
      return false;

    log::debug(": Updating {} scenarios.", flat_scenarios->size());
    for (auto const& scenario : *flat_scenarios) {
      log::debug("Scenario {} configs:", scenario->name()->c_str());
      auto configs = AudioSetConfigurationsFromFlatScenario(scenario);
      for (auto& config : configs) {
        log::debug("\t\t Audio set config: {}", config->name);
      }

      auto [it_begin, it_end] =
          ScenarioToContextTypes(scenario->name()->c_str());
      for (auto it = it_begin; it != it_end; ++it) {
        context_configurations_.insert_or_assign(
            it->second, AudioSetConfigurationsFromFlatScenario(scenario));
      }
    }

    return true;
  }

  bool LoadContent(
      std::vector<std::pair<const char* /*schema*/, const char* /*content*/>>
          config_files,
      std::vector<std::pair<const char* /*schema*/, const char* /*content*/>>
          scenario_files,
      types::CodecLocation location) {
    for (auto [schema, content] : config_files) {
      if (!LoadConfigurationsFromFiles(schema, content, location)) return false;
    }

    for (auto [schema, content] : scenario_files) {
      if (!LoadScenariosFromFiles(schema, content)) return false;
    }
    return true;
  }
};

struct AudioSetConfigurationProvider::impl {
  impl(const AudioSetConfigurationProvider& config_provider)
      : config_provider_(config_provider) {}

  void Initialize(types::CodecLocation location) {
    log::assert_that(!config_provider_impl_, "Config provider not available.");
    config_provider_impl_ =
        std::make_unique<AudioSetConfigurationProviderJson>(location);
  }

  void Cleanup() {
    log::assert_that(config_provider_impl_ != nullptr,
                     "Config provider not available.");
    config_provider_impl_.reset();
  }

  bool IsRunning() { return config_provider_impl_ ? true : false; }

  void Dump(int fd) {
    std::stringstream stream;

    for (LeAudioContextType context : types::kLeAudioContextAllTypesArray) {
      auto confs = Get()->GetConfigurations(context);
      stream << "\n  === Configurations for context type: " << (int)context
             << ", num: " << (confs == nullptr ? 0 : confs->size()) << " \n";
      if (confs && confs->size() > 0) {
        for (const auto& conf : *confs) {
          stream << "  name: " << conf->name << " \n";
          for (const auto direction :
               {types::kLeAudioDirectionSink, types::kLeAudioDirectionSource}) {
            stream << "   ASE configs for direction: "
                   << (direction == types::kLeAudioDirectionSink
                           ? "Sink (speaker)\n"
                           : "Source (microphone)\n");
            for (const auto& ent : conf->confs.get(direction)) {
              stream << "    ASE config: "
                     << "     qos->target latency: " << +ent.qos.target_latency
                     << " \n"
                     << "     qos->retransmission_number: "
                     << +ent.qos.retransmission_number << " \n"
                     << "     qos->max_transport_latency: "
                     << +ent.qos.max_transport_latency << " \n"
                     << "     channel count per ISO stream: "
                     << +ent.codec.GetChannelCountPerIsoStream() << "\n";
            }
          }
        }
      }
    }
    dprintf(fd, "%s", stream.str().c_str());
  }

  const AudioSetConfigurationProvider& config_provider_;
  std::unique_ptr<AudioSetConfigurationProviderJson> config_provider_impl_;
};

static std::unique_ptr<AudioSetConfigurationProvider> config_provider;
std::mutex instance_mutex;

AudioSetConfigurationProvider::AudioSetConfigurationProvider()
    : pimpl_(std::make_unique<AudioSetConfigurationProvider::impl>(*this)) {}

void AudioSetConfigurationProvider::Initialize(types::CodecLocation location) {
  std::scoped_lock<std::mutex> lock(instance_mutex);
  if (!config_provider)
    config_provider = std::make_unique<AudioSetConfigurationProvider>();

  if (!config_provider->pimpl_->IsRunning())
    config_provider->pimpl_->Initialize(location);
}

void AudioSetConfigurationProvider::DebugDump(int fd) {
  std::scoped_lock<std::mutex> lock(instance_mutex);
  if (!config_provider || !config_provider->pimpl_->IsRunning()) {
    dprintf(
        fd,
        "\n AudioSetConfigurationProvider not initialized: config provider: "
        "%d, pimpl: %d \n",
        config_provider != nullptr,
        (config_provider == nullptr ? 0
                                    : config_provider->pimpl_->IsRunning()));
    return;
  }
  dprintf(fd, "\n AudioSetConfigurationProvider: \n");
  config_provider->pimpl_->Dump(fd);
}

void AudioSetConfigurationProvider::Cleanup() {
  std::scoped_lock<std::mutex> lock(instance_mutex);
  if (!config_provider) return;
  if (config_provider->pimpl_->IsRunning()) config_provider->pimpl_->Cleanup();
  config_provider.reset();
}

AudioSetConfigurationProvider* AudioSetConfigurationProvider::Get() {
  return config_provider.get();
}

const set_configurations::AudioSetConfigurations*
AudioSetConfigurationProvider::GetConfigurations(
    ::bluetooth::le_audio::types::LeAudioContextType content_type) const {
  if (pimpl_->IsRunning())
    return pimpl_->config_provider_impl_->GetConfigurationsByContextType(
        content_type);

  return nullptr;
}

bool AudioSetConfigurationProvider::CheckConfigurationIsBiDirSwb(
    const set_configurations::AudioSetConfiguration& set_configuration) const {
  uint8_t dir = 0;

  for (auto direction : {le_audio::types::kLeAudioDirectionSink,
                         le_audio::types::kLeAudioDirectionSource}) {
    for (const auto& conf : set_configuration.confs.get(direction)) {
      if (conf.codec.GetSamplingFrequencyHz() >=
          bluetooth::le_audio::LeAudioCodecConfiguration::kSampleRate32000) {
        dir |= direction;
      }
    }
  }
  return dir == bluetooth::le_audio::types::kLeAudioDirectionBoth;
}

bool AudioSetConfigurationProvider::CheckConfigurationIsDualBiDirSwb(
    const set_configurations::AudioSetConfiguration& set_configuration) const {
  types::BidirectionalPair<uint8_t> swb_direction_counter = {0, 0};

  for (auto direction : {le_audio::types::kLeAudioDirectionSink,
                         le_audio::types::kLeAudioDirectionSource}) {
    auto const& confs = set_configuration.confs.get(direction);
    swb_direction_counter.get(direction) +=
        std::count_if(confs.begin(), confs.end(), [](auto const& cfg) {
          return cfg.codec.GetSamplingFrequencyHz() >=
                 bluetooth::le_audio::LeAudioCodecConfiguration::
                     kSampleRate32000;
        });
  }

  return (swb_direction_counter.sink > 1) && (swb_direction_counter.source > 1);
}

}  // namespace bluetooth::le_audio
