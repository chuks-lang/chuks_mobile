var N=2000, STRIDE=20, FRAMES=10000;
function View(kind,text,kids){ this.kind=kind; this.text=text; this.kids=kids; }
function Row(id){ this.id=id; this.last=0; this.cached=new View("Text","Row "+id+": 0",[]); }
Row.prototype.render=function(v){
  if(v===this.last) return this.cached;
  this.last=v; this.cached=new View("Text","Row "+this.id+": "+v,[]); return this.cached;
};
var ROWS,VALUES,MUT;
function renderList(){ var kids=new Array(N); for(var i=0;i<N;i++) kids[i]=ROWS[i].render(VALUES[i]); return new View("VStack","",kids); }
function reconcile(prev,next){
  if(prev===next) return;
  if(prev.text!==next.text) MUT++;
  var n=next.kids.length;
  for(var i=0;i<n;i++){ if(i<prev.kids.length) reconcile(prev.kids[i],next.kids[i]); else MUT++; }
}
function bench(){
  ROWS=[]; VALUES=[]; MUT=0;
  for(var i=0;i<N;i++){ ROWS.push(new Row(i)); VALUES.push(0); }
  var prev=renderList();
  for(var f=0;f<FRAMES;f++){ for(var i=f%STRIDE;i<N;i+=STRIDE) VALUES[i]++; var next=renderList(); reconcile(prev,next); prev=next; }
  return MUT;
}
var out=(typeof console!=='undefined'&&console.log)?function(s){console.log(s)}:print;
bench();
var t0=Date.now(), c=bench(), dt=Date.now()-t0;
out(c+" | "+(dt*1000/FRAMES).toFixed(2)+" us/frame | "+dt+" ms");
