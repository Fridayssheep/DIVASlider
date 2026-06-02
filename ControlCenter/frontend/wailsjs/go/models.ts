export namespace inputmethod {
	
	export class Config {
	    id: string;
	    httpAddr: string;
	    udpAddr: string;
	    httpEnabled: boolean;
	    udpEnabled: boolean;
	    dllInjection: boolean;
	    joystickSlider: boolean;
	    outputRefreshMillis: number;
	    debug: boolean;
	
	    static createFrom(source: any = {}) {
	        return new Config(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.id = source["id"];
	        this.httpAddr = source["httpAddr"];
	        this.udpAddr = source["udpAddr"];
	        this.httpEnabled = source["httpEnabled"];
	        this.udpEnabled = source["udpEnabled"];
	        this.dllInjection = source["dllInjection"];
	        this.joystickSlider = source["joystickSlider"];
	        this.outputRefreshMillis = source["outputRefreshMillis"];
	        this.debug = source["debug"];
	    }
	}
	export class Info {
	    id: string;
	    name: string;
	    description: string;
	
	    static createFrom(source: any = {}) {
	        return new Info(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.id = source["id"];
	        this.name = source["name"];
	        this.description = source["description"];
	    }
	}

}

export namespace main {
	
	export class MethodRequirement {
	    methodId: string;
	    ok: boolean;
	    severity: string;
	    title: string;
	    message: string;
	    detail?: string;
	    action: string;
	
	    static createFrom(source: any = {}) {
	        return new MethodRequirement(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.methodId = source["methodId"];
	        this.ok = source["ok"];
	        this.severity = source["severity"];
	        this.title = source["title"];
	        this.message = source["message"];
	        this.detail = source["detail"];
	        this.action = source["action"];
	    }
	}

}

export namespace mega39 {
	
	export class StickAxes {
	    leftX: number;
	    leftY: number;
	    rightX: number;
	    rightY: number;
	
	    static createFrom(source: any = {}) {
	        return new StickAxes(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.leftX = source["leftX"];
	        this.leftY = source["leftY"];
	        this.rightX = source["rightX"];
	        this.rightY = source["rightY"];
	    }
	}

}

export namespace output {
	
	export class Status {
	    name: string;
	    ready: boolean;
	    message: string;
	    joystickAxes?: mega39.StickAxes;
	    lastUpdatedMs?: number;
	
	    static createFrom(source: any = {}) {
	        return new Status(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.name = source["name"];
	        this.ready = source["ready"];
	        this.message = source["message"];
	        this.joystickAxes = this.convertValues(source["joystickAxes"], mega39.StickAxes);
	        this.lastUpdatedMs = source["lastUpdatedMs"];
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}

}

export namespace runner {
	
	export class Status {
	    running: boolean;
	    config: inputmethod.Config;
	    input: state.Snapshot;
	    outputs: output.Status[];
	    httpUrl: string;
	
	    static createFrom(source: any = {}) {
	        return new Status(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.running = source["running"];
	        this.config = this.convertValues(source["config"], inputmethod.Config);
	        this.input = this.convertValues(source["input"], state.Snapshot);
	        this.outputs = this.convertValues(source["outputs"], output.Status);
	        this.httpUrl = source["httpUrl"];
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}

}

export namespace state {
	
	export class Snapshot {
	    Sequence: number;
	    UpdatedMillis: number;
	    Buttons: number;
	    Coins: number;
	    Connected: boolean;
	    Slider: number[];
	
	    static createFrom(source: any = {}) {
	        return new Snapshot(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.Sequence = source["Sequence"];
	        this.UpdatedMillis = source["UpdatedMillis"];
	        this.Buttons = source["Buttons"];
	        this.Coins = source["Coins"];
	        this.Connected = source["Connected"];
	        this.Slider = source["Slider"];
	    }
	}

}

