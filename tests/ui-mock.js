/* Udawany most „Tuya” do podglądu interfejsu w przeglądarce (testy, zrzuty ekranu). Nie trafia do APK. */
(function(){
  var mem = {};
  var V2 = {
    '20': {code:'switch_led', type:'Boolean', values:{}, writable:true},
    '21': {code:'work_mode', type:'Enum', values:{range:['white','colour','scene','music']}, writable:true},
    '22': {code:'bright_value_v2', type:'Integer', values:{min:10, max:1000, scale:0, step:1}, writable:true},
    '23': {code:'temp_value_v2', type:'Integer', values:{min:0, max:1000, scale:0, step:1}, writable:true},
    '24': {code:'colour_data_v2', type:'Json', values:{}, writable:true}
  };
  var PLUG = {
    '1': {code:'switch_1', type:'Boolean', values:{}, writable:true},
    '9': {code:'countdown_1', type:'Integer', values:{unit:'s', min:0, max:86400, scale:0, step:1}, writable:true},
    '17': {code:'add_ele', type:'Integer', values:{unit:'kW·h', min:0, max:50000, scale:3, step:100}, writable:false},
    '18': {code:'cur_current', type:'Integer', values:{unit:'mA', min:0, max:30000, scale:0, step:1}, writable:false},
    '19': {code:'cur_power', type:'Integer', values:{unit:'W', min:0, max:80000, scale:1, step:1}, writable:false},
    '20': {code:'cur_voltage', type:'Integer', values:{unit:'V', min:0, max:5000, scale:1, step:1}, writable:false},
    '38': {code:'relay_status', type:'Enum', values:{range:['power_off','power_on','last']}, writable:true}
  };
  var STRIP = {
    '1': {code:'switch_1', type:'Boolean', values:{}, writable:true},
    '2': {code:'switch_2', type:'Boolean', values:{}, writable:true},
    '3': {code:'switch_3', type:'Boolean', values:{}, writable:true},
    '7': {code:'switch_usb1', type:'Boolean', values:{}, writable:true}
  };
  var GARAGE = {
    '1': {code:'switch_1', type:'Boolean', values:{}, writable:true},
    '3': {code:'doorcontact_state', type:'Boolean', values:{}, writable:false}
  };
  var METER = {
    '16': {code:'switch', type:'Boolean', values:{}, writable:true},
    '101': {code:'voltage_a', type:'Integer', values:{unit:'V', min:0, max:3000, scale:1}, writable:false},
    '102': {code:'current_a', type:'Integer', values:{unit:'mA', min:0, max:60000, scale:0}, writable:false},
    '103': {code:'power_a', type:'Integer', values:{unit:'W', min:0, max:99999, scale:0}, writable:false},
    '105': {code:'energy_forword_a', type:'Integer', values:{unit:'kW·h', min:0, max:99999999, scale:2}, writable:false},
    '110': {code:'temp_current', type:'Integer', values:{unit:'°C', min:-200, max:1000, scale:1}, writable:false}
  };
  var devs = [
    {id:'bfgarage0000000001', name:'Brama garażowa', key:'0123456789abcdef', ip:'192.168.1.41', version:'3.3', category:'ckmkzq', mapping:GARAGE, kind:'auto', garageMode:'two'},
    {id:'bflamp00000000001', name:'Lampa salon', key:'0123456789abcdef', ip:'192.168.1.42', version:'3.5', category:'dj', mapping:V2, kind:'auto'},
    {id:'bfled000000000001', name:'Taśma LED kuchnia', key:'0123456789abcdef', ip:'192.168.1.43', version:'3.4', category:'dd', mapping:V2, kind:'auto'},
    {id:'bfplug00000000001', name:'Pralka', key:'0123456789abcdef', ip:'192.168.1.44', version:'3.3', category:'cz', mapping:PLUG, kind:'auto'},
    {id:'bfplug00000000002', name:'Lampka przy łóżku', key:'0123456789abcdef', ip:'192.168.1.45', version:'3.3', category:'cz', mapping:PLUG, kind:'auto'},
    {id:'bfstrip0000000001', name:'Listwa biurko', key:'0123456789abcdef', ip:'192.168.1.46', version:'3.4', category:'pc', mapping:STRIP, kind:'auto'},
    {id:'bfplug00000000003', name:'Gniazdo wędzarnia', key:'0123456789abcdef', ip:'192.168.1.47', version:'3.3', category:'cz', mapping:PLUG, kind:'auto'},
    {id:'bfmeter0000000001', name:'Licznik PC', key:'0123456789abcdef', ip:'192.168.1.48', version:'3.5', category:'zndb', mapping:METER, kind:'auto'},
    {id:'bfirr000000000001', name:'Nawodnienie ogród', key:'0123456789abcdef', ip:'', version:'auto', category:'tdq', mapping:{}, kind:'auto', online:false},
    {id:'bfbat000000000001', name:'Akumulator 20 V', key:'0123456789abcdef', ip:'', version:'auto', category:'dcb', mapping:{}, kind:'auto', online:true},
    {id:'bfsw0000000000001', name:'Światło garaż', key:'0123456789abcdef', ip:'', version:'auto', category:'tdq', mapping:{}, kind:'auto', online:true}
  ];
  var state = {
    bfgarage0000000001: {'1':false, '3':false},
    bflamp00000000001: {'20':true, '21':'white', '22':650, '23':300, '24':'000003e803e8'},
    bfled000000000001: {'20':true, '21':'colour', '22':1000, '23':0, '24':'00f003e80320'},
    bfplug00000000001: {'1':true, '9':0, '17':12345, '18':2450, '19':5320, '20':2318, '38':'last'},
    bfplug00000000002: {'1':false, '9':0, '17':230, '18':0, '19':0, '20':2321, '38':'power_off'},
    bfstrip0000000001: {'1':true, '2':false, '3':true, '7':true},
    bfmeter0000000001: {'16':true, '101':2415, '102':521, '103':240, '105':993654, '110':260}
  };
  var hash = location.hash || '';
  if (hash.indexOf('empty') < 0) mem['k_devices'] = JSON.stringify(devs);
  mem['k_settings'] = JSON.stringify({theme: hash.indexOf('dark') >= 0 ? 'dark' : 'light', autoRefresh:false});
  var away = hash.indexOf('away') >= 0;
  function reply(id, r, ms){ setTimeout(function(){ window.__tuya(id, r); }, ms || 120); }
  window.__calls = [];
  window.Tuya = {
    load: function(k){ return mem['k_' + k] == null ? null : mem['k_' + k]; },
    save: function(k, v){ if (v == null) delete mem['k_' + k]; else mem['k_' + k] = v; },
    isNight: function(){ return hash.indexOf('dark') >= 0; },
    setDark: function(){}, openUrl: function(){}, openTailscale: function(){ window.__calls.push(['openTailscale']); },
    vibrate: function(){}, share: function(t){ window.__shared = t; }, version: function(){ return '1.0.test'; },
    netInfo: function(){ return JSON.stringify(away ? {wifi:false, vpn:false, online:true, ip:null} : {wifi:true, vpn:false, online:true, ip:'192.168.1.20', broadcast:'192.168.1.255'}); },
    status: function(id, devJson){
      var d = JSON.parse(devJson); window.__calls.push(['status', d.id]);
      if (away || !state[d.id]) return reply(id, {ok:false, kind:'UNREACHABLE', error:'Brak połączenia z urządzeniem. Sprawdź, czy jest włączone i czy telefon jest w sieci domowej albo ma włączony Tailscale.'}, 300);
      reply(id, {ok:true, data:{dps:JSON.parse(JSON.stringify(state[d.id])), device22:false}});
    },
    set: function(id, devJson, dpsJson){
      var d = JSON.parse(devJson), dps = JSON.parse(dpsJson); window.__calls.push(['set', d.id, dps]);
      if (!state[d.id]) return reply(id, {ok:false, kind:'UNREACHABLE', error:'Brak połączenia z urządzeniem.'}, 300);
      for (var k in dps) state[d.id][k] = dps[k];
      if (d.id === 'bfgarage0000000001' && '1' in dps) state[d.id]['3'] = dps['1'];
      reply(id, {ok:true, data:{dps:JSON.parse(JSON.stringify(state[d.id])), device22:false}});
    },
    detect: function(id, devJson){ var d = JSON.parse(devJson); reply(id, {ok:true, data:{version:'3.3', dps:state[d.id] || {}, device22:false}}); },
    scan: function(id){ reply(id, {ok:true, data:devs.slice(0, 6).map(function(d){ return {id:d.id, ip:d.ip, version:d.version}; }).concat([{id:'bfunknown000000001', ip:'192.168.1.90', version:'3.5'}])}, 400); },
    cloudImport: function(id, region, aid, sec){
      window.__calls.push(['cloudImport', region, aid]);
      if (sec !== 'dobry') return reply(id, {ok:false, kind:'CLOUD', error:'Chmura odrzuciła podpis: sprawdź Access ID i Access Secret oraz czy telefon ma prawidłową godzinę.'}, 300);
      reply(id, {ok:true, data:devs.map(function(d){ return {id:d.id, name:d.name, key:d.key, category:d.category, mapping:d.mapping, online:d.online !== false}; })}, 300);
    }
  };
})();
